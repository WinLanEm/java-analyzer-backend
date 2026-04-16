package actions;

import dto.AnalyzerEdge;
import dto.AnalyzerNode;
import dto.CurrentContextDTO;
import dto.ExecutionStep;
import dto.GraphDTO;
import dto.ObjectInstance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimulateExecutionAction {
    private static final String RUNTIME_ERROR_NODE_ID = "runtime-error";
    private static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "^(?:final\\s+)?[A-Za-z_$][A-Za-z0-9_$]*(?:<[^>]+>)?(?:\\[\\])?\\s+(.+)$"
    );
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+)$"
    );
    private static final Pattern FIELD_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+)$"
    );
    private static final Pattern FIELD_ACCESS_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)$"
    );
    private static final Pattern OBJECT_CREATION_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(.*\\)$"
    );
    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$"
    );
    private static final int MAX_STEPS = 10_000;
    private static final AtomicLong OBJECT_ID_COUNTER = new AtomicLong(1);
    private Map<Long, RuntimeObject> heap = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> classFieldDefaults = new LinkedHashMap<>();

    public List<ExecutionStep> execute(Map<String, GraphDTO> graphs) {
        if (graphs == null || graphs.isEmpty()) {
            return List.of();
        }

        String entryMethod = findEntryMethod(graphs);
        ExecutionContext entryContext = createContext(entryMethod, graphs.get(entryMethod), Map.of(), null);
        if (entryContext.activeNodeId() == null) {
            return List.of();
        }

        Map<String, Map<String, AnalyzerNode>> nodesByMethod = indexNodes(graphs);
        Map<String, Map<String, List<AnalyzerEdge>>> edgesByMethod = indexEdges(graphs);
        Deque<ExecutionContext> callStack = new ArrayDeque<>();
        List<ExecutionStep> trace = new ArrayList<>();
        heap = new LinkedHashMap<>();
        classFieldDefaults = indexFields(graphs);

        callStack.push(entryContext);
        int step = 1;

        while (!callStack.isEmpty() && step <= MAX_STEPS) {
            ExecutionContext context = callStack.peek();
            AnalyzerNode currentNode = nodesByMethod
                    .getOrDefault(context.methodSignature(), Map.of())
                    .get(context.activeNodeId());

            if (currentNode == null) {
                callStack.pop();
                continue;
            }

            collectGarbage(callStack);
            trace.add(new ExecutionStep(
                    step,
                    context.methodSignature(),
                    currentNode.id(),
                    snapshotMemory(context.localVariables()),
                    callStackView(callStack),
                    snapshotHeap(heap),
                    currentContext(graphs.get(context.methodSignature()))
            ));

            try {
                if (isThrowNode(currentNode)) {
                    context.markUncaughtException();
                }

                String callTarget = resolveCallTarget(currentNode, context.localVariables(), heap, graphs);
                if (currentNode.isCall() && callTarget != null) {
                    enterMethodCall(currentNode, callTarget, context, graphs, edgesByMethod, callStack, heap);
                    collectGarbage(callStack);
                    step++;
                    continue;
                }

                if (isReturnNode(currentNode)) {
                    Object returnValue = evaluateReturn(currentNode.label(), context.localVariables());
                    callStack.pop();
                    if (!callStack.isEmpty() && context.returnTargetVariable() != null) {
                        callStack.peek().localVariables().put(context.returnTargetVariable(), returnValue);
                    }
                    collectGarbage(callStack);
                    step++;
                    continue;
                }

                if ("action".equals(currentNode.type())) {
                    executeAction(currentNode.label(), context.localVariables());
                }

                AnalyzerEdge nextEdge = chooseNextEdge(
                        currentNode,
                        edgesByMethod.getOrDefault(context.methodSignature(), Map.of())
                                .getOrDefault(currentNode.id(), List.of()),
                        context.localVariables()
                );
                context.setActiveNodeId(nextEdge == null ? null : nextEdge.target());
            } catch (VirtualExecutionException exception) {
                context.localVariables().put("__error", exception.getMessage());
                collectGarbage(callStack);
                trace.add(new ExecutionStep(
                        step + 1,
                        context.methodSignature(),
                        RUNTIME_ERROR_NODE_ID,
                        snapshotMemory(context.localVariables()),
                        callStackView(callStack),
                        snapshotHeap(heap),
                        currentContext(graphs.get(context.methodSignature()))
                ));
                break;
            }

            if (context.activeNodeId() == null && !callStack.isEmpty() && callStack.peek() == context) {
                popCompletedContext(callStack, graphs);
            }

            collectGarbage(callStack);
            step++;
        }

        return List.copyOf(trace);
    }

    private String findEntryMethod(Map<String, GraphDTO> graphs) {
        return graphs.keySet().stream()
                .filter(signature -> signature.endsWith(".main"))
                .findFirst()
                .orElseGet(() -> graphs.keySet().iterator().next());
    }

    private ExecutionContext createContext(
            String methodSignature,
            GraphDTO graph,
            Map<String, Object> arguments,
            String returnTargetVariable
    ) {
        Map<String, Object> localVariables = new LinkedHashMap<>();
        for (String parameter : graph.parameters()) {
            localVariables.put(parameter, arguments.get(parameter));
        }
        if (arguments.containsKey("this")) {
            localVariables.put("this", arguments.get("this"));
        }

        String activeNodeId = graph.nodes().isEmpty() ? null : graph.nodes().getFirst().id();
        return new ExecutionContext(methodSignature, activeNodeId, localVariables, returnTargetVariable);
    }

    private Map<String, Map<String, AnalyzerNode>> indexNodes(Map<String, GraphDTO> graphs) {
        Map<String, Map<String, AnalyzerNode>> result = new LinkedHashMap<>();
        for (Map.Entry<String, GraphDTO> graphEntry : graphs.entrySet()) {
            Map<String, AnalyzerNode> nodes = new LinkedHashMap<>();
            for (AnalyzerNode node : graphEntry.getValue().nodes()) {
                nodes.put(node.id(), node);
            }
            result.put(graphEntry.getKey(), nodes);
        }
        return result;
    }

    private Map<String, Map<String, List<AnalyzerEdge>>> indexEdges(Map<String, GraphDTO> graphs) {
        Map<String, Map<String, List<AnalyzerEdge>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, GraphDTO> graphEntry : graphs.entrySet()) {
            Map<String, List<AnalyzerEdge>> edgesBySource = new LinkedHashMap<>();
            for (AnalyzerEdge edge : graphEntry.getValue().edges()) {
                edgesBySource.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            }
            result.put(graphEntry.getKey(), edgesBySource);
        }
        return result;
    }

    private Map<String, Map<String, Object>> indexFields(Map<String, GraphDTO> graphs) {
        Map<String, Map<String, Object>> fieldsByClass = new LinkedHashMap<>();
        for (GraphDTO graph : graphs.values()) {
            fieldsByClass.putIfAbsent(graph.className(), graph.classFields());
        }
        return fieldsByClass;
    }

    private void enterMethodCall(
            AnalyzerNode callNode,
            String callTarget,
            ExecutionContext callerContext,
            Map<String, GraphDTO> graphs,
            Map<String, Map<String, List<AnalyzerEdge>>> edgesByMethod,
            Deque<ExecutionContext> callStack,
            Map<Long, RuntimeObject> heap
    ) {
        GraphDTO targetGraph = graphs.get(callTarget);
        List<Object> argumentValues = evaluateCallArguments(callNode, callerContext.localVariables());
        Map<String, Object> arguments = new LinkedHashMap<>();
        Long receiverPointer = resolveReceiverPointer(callNode, callerContext.localVariables());

        if (receiverPointer != null) {
            arguments.put("this", receiverPointer);
        }

        for (int i = 0; i < targetGraph.parameters().size(); i++) {
            Object value = i < argumentValues.size() ? argumentValues.get(i) : null;
            arguments.put(targetGraph.parameters().get(i), value);
        }

        AnalyzerEdge nextEdge = chooseNextEdge(
                callNode,
                edgesByMethod.getOrDefault(callerContext.methodSignature(), Map.of())
                        .getOrDefault(callNode.id(), List.of()),
                callerContext.localVariables()
        );
        callerContext.setActiveNodeId(nextEdge == null ? null : nextEdge.target());

        callStack.push(createContext(
                callTarget,
                targetGraph,
                arguments,
                extractAssignmentTarget(callNode.label())
        ));
    }

    private void popCompletedContext(Deque<ExecutionContext> callStack, Map<String, GraphDTO> graphs) {
        ExecutionContext completedContext = callStack.pop();
        if (!completedContext.isUncaughtException() || callStack.isEmpty()) {
            return;
        }

        ExecutionContext callerContext = callStack.peek();
        callerContext.markUncaughtException();
        callerContext.setActiveNodeId(exceptionExitNodeId(graphs.get(callerContext.methodSignature())));
    }

    private CurrentContextDTO currentContext(GraphDTO graph) {
        if (graph == null) {
            return new CurrentContextDTO(null, null);
        }
        return new CurrentContextDTO(graph.className(), graph.methodName());
    }

    private String exceptionExitNodeId(GraphDTO graph) {
        if (graph == null) {
            return null;
        }

        for (AnalyzerNode node : graph.nodes()) {
            if ("exit-exception".equals(node.id())) {
                return node.id();
            }
        }
        return null;
    }

    private String resolveCallTarget(
            AnalyzerNode callNode,
            Map<String, Object> localVariables,
            Map<Long, RuntimeObject> heap,
            Map<String, GraphDTO> graphs
    ) {
        if (!callNode.isCall()) {
            return null;
        }

        if (callNode.callReceiver() != null) {
            Long pointer = resolveReceiverPointer(callNode, localVariables);
            if (pointer == null) {
                return null;
            }

            RuntimeObject instance = heap.get(pointer);
            if (instance == null) {
                return null;
            }

            String target = instance.className() + "." + callNode.callMethodName();
            return graphs.containsKey(target) ? target : null;
        }

        return graphs.containsKey(callNode.callTarget()) ? callNode.callTarget() : null;
    }

    private Long resolveReceiverPointer(AnalyzerNode callNode, Map<String, Object> localVariables) {
        String receiver = callNode.callReceiver();
        if (receiver == null) {
            return null;
        }

        Object value = localVariables.get(receiver);
        return value instanceof Number number ? number.longValue() : null;
    }

    private List<Object> evaluateCallArguments(AnalyzerNode callNode, Map<String, Object> memory) {
        String methodName = callNode.callMethodName() == null
                ? callNode.callTarget().substring(callNode.callTarget().lastIndexOf('.') + 1)
                : callNode.callMethodName();
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\((.*)\\)").matcher(callNode.label());
        if (!matcher.find() || matcher.group(1).isBlank()) {
            return List.of();
        }

        List<Object> arguments = new ArrayList<>();
        for (String argument : matcher.group(1).split(",")) {
            arguments.add(evaluateValue(argument.trim(), memory));
        }
        return arguments;
    }

    private String extractAssignmentTarget(String label) {
        String statement = cleanStatement(label);
        Matcher declarationMatcher = DECLARATION_PATTERN.matcher(statement);
        if (declarationMatcher.matches()) {
            statement = declarationMatcher.group(1);
        }

        Matcher assignmentMatcher = ASSIGNMENT_PATTERN.matcher(statement);
        return assignmentMatcher.matches() ? assignmentMatcher.group(1) : null;
    }

    private AnalyzerEdge chooseNextEdge(
            AnalyzerNode currentNode,
            List<AnalyzerEdge> outgoingEdges,
            Map<String, Object> memory
    ) {
        if (outgoingEdges.isEmpty()) {
            return null;
        }

        if (!"condition".equals(currentNode.type())) {
            return outgoingEdges.getFirst();
        }

        String expectedLabel = evaluateCondition(currentNode.label(), memory) ? "true" : "false";
        for (AnalyzerEdge edge : outgoingEdges) {
            if (expectedLabel.equals(edge.label())) {
                return edge;
            }
        }

        return outgoingEdges.getFirst();
    }

    private boolean isReturnNode(AnalyzerNode node) {
        return "action".equals(node.type()) && cleanStatement(node.label()).startsWith("return");
    }

    private boolean isThrowNode(AnalyzerNode node) {
        return "action".equals(node.type()) && cleanStatement(node.label()).startsWith("throw");
    }

    private Object evaluateReturn(String label, Map<String, Object> memory) {
        String expression = cleanStatement(label).replaceFirst("^return\\s*", "");
        return expression.isBlank() ? null : evaluateValue(expression, memory);
    }

    private void executeAction(String label, Map<String, Object> memory) {
        String statement = cleanStatement(label);
        if (statement.startsWith("throw") || "break".equals(statement) || "continue".equals(statement)) {
            return;
        }

        Matcher declarationMatcher = DECLARATION_PATTERN.matcher(statement);
        if (declarationMatcher.matches()) {
            executeDeclaration(declarationMatcher.group(1), memory);
            return;
        }

        executeAssignment(statement, memory);
    }

    private void executeDeclaration(String declarationBody, Map<String, Object> memory) {
        for (String declarator : declarationBody.split(",")) {
            executeAssignment(declarator.trim(), memory);
        }
    }

    private void executeAssignment(String statement, Map<String, Object> memory) {
        Matcher fieldAssignmentMatcher = FIELD_ASSIGNMENT_PATTERN.matcher(statement);
        if (fieldAssignmentMatcher.matches()) {
            assignField(
                    fieldAssignmentMatcher.group(1),
                    fieldAssignmentMatcher.group(2),
                    evaluateValue(fieldAssignmentMatcher.group(3), memory),
                    memory
            );
            return;
        }

        Matcher assignmentMatcher = ASSIGNMENT_PATTERN.matcher(statement);
        if (!assignmentMatcher.matches()) {
            return;
        }

        String variableName = assignmentMatcher.group(1);
        String expression = assignmentMatcher.group(2);
        memory.put(variableName, evaluateValue(expression, memory));
    }

    private boolean evaluateCondition(String expression, Map<String, Object> memory) {
        String cleanExpression = cleanStatement(expression);

        Matcher comparisonMatcher = COMPARISON_PATTERN.matcher(cleanExpression);
        if (comparisonMatcher.matches()) {
            Object left = evaluateValue(comparisonMatcher.group(1), memory);
            Object right = evaluateValue(comparisonMatcher.group(3), memory);
            return compare(left, comparisonMatcher.group(2), right);
        }

        Object value = evaluateValue(cleanExpression, memory);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private Object evaluateValue(String expression, Map<String, Object> memory) {
        String cleanExpression = cleanStatement(expression);

        if ("true".equals(cleanExpression)) {
            return true;
        }
        if ("false".equals(cleanExpression)) {
            return false;
        }
        if (cleanExpression.startsWith("\"") && cleanExpression.endsWith("\"")) {
            return cleanExpression.substring(1, cleanExpression.length() - 1);
        }
        if (memory.containsKey(cleanExpression)) {
            return memory.get(cleanExpression);
        }
        Matcher fieldAccessMatcher = FIELD_ACCESS_PATTERN.matcher(cleanExpression);
        if (fieldAccessMatcher.matches()) {
            return readField(fieldAccessMatcher.group(1), fieldAccessMatcher.group(2), memory);
        }
        Matcher objectCreationMatcher = OBJECT_CREATION_PATTERN.matcher(cleanExpression);
        if (objectCreationMatcher.matches()) {
            return allocateObject(objectCreationMatcher.group(1), memory);
        }

        return evaluateArithmetic(cleanExpression, memory);
    }

    private long allocateObject(String className, Map<String, Object> memory) {
        long objectId = OBJECT_ID_COUNTER.getAndIncrement();
        RuntimeObject instance = new RuntimeObject(objectId, className, initializeFields(className, memory));
        heap.put(objectId, instance);
        return objectId;
    }

    private Map<String, Object> initializeFields(String className, Map<String, Object> memory) {
        Map<String, Object> initializedFields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : classFields(className).entrySet()) {
            initializedFields.put(field.getKey(), initializeFieldValue(field.getValue(), memory));
        }
        return initializedFields;
    }

    private Object initializeFieldValue(Object value, Map<String, Object> memory) {
        if (value instanceof String stringValue && OBJECT_CREATION_PATTERN.matcher(stringValue).matches()) {
            return evaluateValue(stringValue, memory);
        }
        return value;
    }

    private Map<String, Object> classFields(String className) {
        return classFieldDefaults.getOrDefault(className, Map.of());
    }

    private void assignField(String receiver, String fieldName, Object value, Map<String, Object> memory) {
        Long pointer = resolvePointer(receiver, memory);
        if (pointer == null) {
            return;
        }

        RuntimeObject instance = heap.get(pointer);
        if (instance != null) {
            instance.fields().put(fieldName, value);
        }
    }

    private Object readField(String receiver, String fieldName, Map<String, Object> memory) {
        Long pointer = resolvePointer(receiver, memory);
        if (pointer == null) {
            return 0;
        }

        RuntimeObject instance = heap.get(pointer);
        if (instance == null) {
            return 0;
        }

        return instance.fields().getOrDefault(fieldName, 0);
    }

    private Long resolvePointer(String variableName, Map<String, Object> memory) {
        Object value = memory.get(variableName);
        return value instanceof Number number ? number.longValue() : null;
    }

    private int evaluateArithmetic(String expression, Map<String, Object> memory) {
        List<String> tokens = tokenizeArithmetic(expression);
        if (tokens.isEmpty()) {
            return 0;
        }

        int result = resolveInteger(tokens.getFirst(), memory);
        for (int i = 1; i < tokens.size() - 1; i += 2) {
            String operator = tokens.get(i);
            int value = resolveInteger(tokens.get(i + 1), memory);
            result = switch (operator) {
                case "+" -> result + value;
                case "-" -> result - value;
                case "*" -> result * value;
                case "/" -> {
                    if (value == 0) {
                        throw new VirtualExecutionException("Division by zero.");
                    }
                    yield result / value;
                }
                default -> result;
            };
        }

        return result;
    }

    private List<String> tokenizeArithmetic(String expression) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "[A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z_$][A-Za-z0-9_$]*|[A-Za-z_$][A-Za-z0-9_$]*|-?\\d+|[+\\-*/]"
        ).matcher(expression);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private int resolveInteger(String token, Map<String, Object> memory) {
        Matcher fieldAccessMatcher = FIELD_ACCESS_PATTERN.matcher(token);
        if (fieldAccessMatcher.matches()) {
            Object fieldValue = readField(fieldAccessMatcher.group(1), fieldAccessMatcher.group(2), memory);
            return fieldValue instanceof Number number ? number.intValue() : 0;
        }

        Object memoryValue = memory.get(token);
        if (memoryValue instanceof Number number) {
            return number.intValue();
        }
        if (memoryValue instanceof Boolean booleanValue) {
            return booleanValue ? 1 : 0;
        }
        return Integer.parseInt(token);
    }

    private boolean compare(Object left, String operator, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            int leftValue = leftNumber.intValue();
            int rightValue = rightNumber.intValue();
            return switch (operator) {
                case "==" -> leftValue == rightValue;
                case "!=" -> leftValue != rightValue;
                case ">" -> leftValue > rightValue;
                case "<" -> leftValue < rightValue;
                case ">=" -> leftValue >= rightValue;
                case "<=" -> leftValue <= rightValue;
                default -> false;
            };
        }

        return switch (operator) {
            case "==" -> left.equals(right);
            case "!=" -> !left.equals(right);
            default -> false;
        };
    }

    private String cleanStatement(String statement) {
        return statement == null ? "" : statement.trim().replaceAll(";$", "").trim();
    }

    private Map<String, Object> snapshotMemory(Map<String, Object> memory) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : memory.entrySet()) {
            snapshot.put(entry.getKey(), snapshotValue(entry.getValue()));
        }
        return snapshot;
    }

    private Object snapshotValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                snapshot.put(String.valueOf(entry.getKey()), snapshotValue(entry.getValue()));
            }
            return snapshot;
        }

        if (value instanceof List<?> listValue) {
            List<Object> snapshot = new ArrayList<>();
            for (Object item : listValue) {
                snapshot.add(snapshotValue(item));
            }
            return List.copyOf(snapshot);
        }

        return value;
    }

    private List<ObjectInstance> snapshotHeap(Map<Long, RuntimeObject> heap) {
        List<ObjectInstance> snapshot = new ArrayList<>();
        for (RuntimeObject instance : heap.values()) {
            snapshot.add(new ObjectInstance(instance.id(), instance.className()));
        }
        return List.copyOf(snapshot);
    }

    private void collectGarbage(Deque<ExecutionContext> callStack) {
        Set<Long> reachable = new HashSet<>();
        for (ExecutionContext context : callStack) {
            for (Object value : context.localVariables().values()) {
                markReachable(value, reachable);
            }
        }
        heap.keySet().removeIf(objectId -> !reachable.contains(objectId));
    }

    private void markReachable(Object value, Set<Long> reachable) {
        if (!(value instanceof Number number)) {
            return;
        }

        long objectId = number.longValue();
        if (!heap.containsKey(objectId) || !reachable.add(objectId)) {
            return;
        }

        RuntimeObject instance = heap.get(objectId);
        for (Object fieldValue : instance.fields().values()) {
            markReachable(fieldValue, reachable);
        }
    }

    private List<String> callStackView(Deque<ExecutionContext> callStack) {
        return callStack.stream()
                .map(ExecutionContext::methodSignature)
                .toList();
    }

    private static final class ExecutionContext {
        private final String methodSignature;
        private String activeNodeId;
        private final Map<String, Object> localVariables;
        private final String returnTargetVariable;
        private boolean uncaughtException;

        private ExecutionContext(
                String methodSignature,
                String activeNodeId,
                Map<String, Object> localVariables,
                String returnTargetVariable
        ) {
            this.methodSignature = methodSignature;
            this.activeNodeId = activeNodeId;
            this.localVariables = localVariables;
            this.returnTargetVariable = returnTargetVariable;
        }

        private String methodSignature() {
            return methodSignature;
        }

        private String activeNodeId() {
            return activeNodeId;
        }

        private void setActiveNodeId(String activeNodeId) {
            this.activeNodeId = activeNodeId;
        }

        private Map<String, Object> localVariables() {
            return localVariables;
        }

        private String returnTargetVariable() {
            return returnTargetVariable;
        }

        private boolean isUncaughtException() {
            return uncaughtException;
        }

        private void markUncaughtException() {
            uncaughtException = true;
        }
    }

    private static final class VirtualExecutionException extends RuntimeException {
        private VirtualExecutionException(String message) {
            super(message);
        }
    }

    private record RuntimeObject(long id, String className, Map<String, Object> fields) {
    }
}
