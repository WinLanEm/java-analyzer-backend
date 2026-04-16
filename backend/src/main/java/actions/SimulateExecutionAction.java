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
    private static final Pattern COMPOUND_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*([+\\-*/])=\\s*(.+)$"
    );
    private static final Pattern POSTFIX_UPDATE_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*(\\+\\+|--)$"
    );
    private static final Pattern PREFIX_UPDATE_PATTERN = Pattern.compile(
            "^(\\+\\+|--)\\s*([A-Za-z_$][A-Za-z0-9_$]*)$"
    );
    private static final Pattern ARRAY_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[([^\\]]+)]\\s*=\\s*(.+)$"
    );
    private static final Pattern FIELD_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+)$"
    );
    private static final Pattern ARRAY_ACCESS_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[([^\\]]+)]$"
    );
    private static final Pattern ARRAY_LENGTH_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.length$"
    );
    private static final Pattern FIELD_ACCESS_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)$"
    );
    private static final Pattern ARRAY_CREATION_INITIALIZER_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[\\]\\s*\\{(.*)}$"
    );
    private static final Pattern ARRAY_CREATION_SIZED_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[(.+)]$"
    );
    private static final Pattern OBJECT_CREATION_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(.*\\)$"
    );
    private static final Pattern INTRINSIC_CALL_PATTERN = Pattern.compile(
            "^(Math\\.(?:max|min|abs|pow)|String\\.valueOf)\\s*\\((.*)\\)$"
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

                executeNode(currentNode, context.localVariables());

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

    private void executeNode(AnalyzerNode node, Map<String, Object> memory) {
        if (!"action".equals(node.type())) {
            return;
        }

        executeAction(node.label(), memory);
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
        for (String argument : splitTopLevelComma(matcher.group(1))) {
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

        if (statement.startsWith("System.out.println")) {
            evaluatePrintArguments(statement, memory);
            return;
        }

        if (executeAssignment(statement, memory)) {
            return;
        }

        Matcher declarationMatcher = DECLARATION_PATTERN.matcher(statement);
        if (declarationMatcher.matches()) {
            executeDeclaration(declarationMatcher.group(1), memory);
        }
    }

    private void executeDeclaration(String declarationBody, Map<String, Object> memory) {
        for (String declarator : splitTopLevelComma(declarationBody)) {
            executeAssignment(declarator.trim(), memory);
        }
    }

    private boolean executeAssignment(String statement, Map<String, Object> memory) {
        String cleanStatement = cleanStatement(statement);

        Matcher compoundAssignmentMatcher = COMPOUND_ASSIGNMENT_PATTERN.matcher(cleanStatement);
        if (compoundAssignmentMatcher.matches()) {
            applyCompoundAssignment(
                    compoundAssignmentMatcher.group(1),
                    compoundAssignmentMatcher.group(2),
                    compoundAssignmentMatcher.group(3),
                    memory
            );
            return true;
        }

        Matcher postfixUpdateMatcher = POSTFIX_UPDATE_PATTERN.matcher(cleanStatement);
        if (postfixUpdateMatcher.matches()) {
            applyVariableUpdate(postfixUpdateMatcher.group(1), postfixUpdateMatcher.group(2), memory);
            return true;
        }

        Matcher prefixUpdateMatcher = PREFIX_UPDATE_PATTERN.matcher(cleanStatement);
        if (prefixUpdateMatcher.matches()) {
            applyVariableUpdate(prefixUpdateMatcher.group(2), prefixUpdateMatcher.group(1), memory);
            return true;
        }

        Matcher arrayAssignmentMatcher = ARRAY_ASSIGNMENT_PATTERN.matcher(cleanStatement);
        if (arrayAssignmentMatcher.matches()) {
            writeArrayElement(
                    arrayAssignmentMatcher.group(1),
                    evaluateExpression(arrayAssignmentMatcher.group(2), memory),
                    evaluateExpression(arrayAssignmentMatcher.group(3), memory),
                    memory
            );
            return true;
        }

        Matcher fieldAssignmentMatcher = FIELD_ASSIGNMENT_PATTERN.matcher(cleanStatement);
        if (fieldAssignmentMatcher.matches()) {
            assignField(
                    fieldAssignmentMatcher.group(1),
                    fieldAssignmentMatcher.group(2),
                    evaluateExpression(fieldAssignmentMatcher.group(3), memory),
                    memory
            );
            return true;
        }

        Matcher assignmentMatcher = ASSIGNMENT_PATTERN.matcher(cleanStatement);
        if (!assignmentMatcher.matches()) {
            return false;
        }

        String variableName = assignmentMatcher.group(1);
        String expression = assignmentMatcher.group(2);
        Object newValue = evaluateExpression(expression, memory);
        memory.put(variableName, newValue);
        return true;
    }

    private void applyVariableUpdate(String variableName, String operator, Map<String, Object> memory) {
        int currentValue = asInt(evaluateExpression(variableName, memory));
        memory.put(variableName, "++".equals(operator) ? currentValue + 1 : currentValue - 1);
    }

    private void applyCompoundAssignment(
            String variableName,
            String operator,
            String expression,
            Map<String, Object> memory
    ) {
        int currentValue = asInt(evaluateExpression(variableName, memory));
        int operandValue = asInt(evaluateExpression(expression, memory));
        int nextValue = switch (operator) {
            case "+" -> currentValue + operandValue;
            case "-" -> currentValue - operandValue;
            case "*" -> currentValue * operandValue;
            case "/" -> {
                if (operandValue == 0) {
                    throw new VirtualExecutionException("Division by zero.");
                }
                yield currentValue / operandValue;
            }
            default -> currentValue;
        };
        memory.put(variableName, nextValue);
    }

    private void evaluatePrintArguments(String statement, Map<String, Object> memory) {
        int openParenthesis = statement.indexOf('(');
        int closeParenthesis = statement.lastIndexOf(')');
        if (openParenthesis < 0 || closeParenthesis <= openParenthesis) {
            return;
        }

        String arguments = statement.substring(openParenthesis + 1, closeParenthesis);
        for (String argument : splitTopLevelComma(arguments)) {
            if (!argument.isBlank()) {
                evaluateValue(argument.trim(), memory);
            }
        }
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
        return evaluateExpression(expression, memory);
    }

    private Object evaluateExpression(String expression, Map<String, Object> memory) {
        String cleanExpression = cleanStatement(expression);
        cleanExpression = stripOuterParentheses(cleanExpression);

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
        Matcher intrinsicMatcher = INTRINSIC_CALL_PATTERN.matcher(cleanExpression);
        if (intrinsicMatcher.matches()) {
            return evaluateIntrinsic(intrinsicMatcher.group(1), intrinsicMatcher.group(2), memory);
        }
        Matcher arrayInitializerMatcher = ARRAY_CREATION_INITIALIZER_PATTERN.matcher(cleanExpression);
        if (arrayInitializerMatcher.matches()) {
            return allocateInitializedArray(arrayInitializerMatcher.group(1), arrayInitializerMatcher.group(2), memory);
        }
        Matcher sizedArrayMatcher = ARRAY_CREATION_SIZED_PATTERN.matcher(cleanExpression);
        if (sizedArrayMatcher.matches()) {
            return allocateSizedArray(sizedArrayMatcher.group(1), evaluateExpression(sizedArrayMatcher.group(2), memory));
        }
        Matcher objectCreationMatcher = OBJECT_CREATION_PATTERN.matcher(cleanExpression);
        if (objectCreationMatcher.matches()) {
            return allocateObject(objectCreationMatcher.group(1), memory);
        }

        int additiveOperatorIndex = findTopLevelOperator(cleanExpression, "+-");
        if (additiveOperatorIndex > 0) {
            Object left = evaluateExpression(cleanExpression.substring(0, additiveOperatorIndex), memory);
            Object right = evaluateExpression(cleanExpression.substring(additiveOperatorIndex + 1), memory);
            return switch (cleanExpression.charAt(additiveOperatorIndex)) {
                case '+' -> asInt(left) + asInt(right);
                case '-' -> asInt(left) - asInt(right);
                default -> 0;
            };
        }

        int multiplicativeOperatorIndex = findTopLevelOperator(cleanExpression, "*/");
        if (multiplicativeOperatorIndex > 0) {
            Object left = evaluateExpression(cleanExpression.substring(0, multiplicativeOperatorIndex), memory);
            Object right = evaluateExpression(cleanExpression.substring(multiplicativeOperatorIndex + 1), memory);
            return switch (cleanExpression.charAt(multiplicativeOperatorIndex)) {
                case '*' -> asInt(left) * asInt(right);
                case '/' -> {
                    int divisor = asInt(right);
                    if (divisor == 0) {
                        throw new VirtualExecutionException("Division by zero.");
                    }
                    yield asInt(left) / divisor;
                }
                default -> 0;
            };
        }

        Matcher arrayLengthMatcher = ARRAY_LENGTH_PATTERN.matcher(cleanExpression);
        if (arrayLengthMatcher.matches()) {
            return readArrayLength(arrayLengthMatcher.group(1), memory);
        }
        Matcher arrayAccessMatcher = ARRAY_ACCESS_PATTERN.matcher(cleanExpression);
        if (arrayAccessMatcher.matches()) {
            return readArrayElement(arrayAccessMatcher.group(1), evaluateValue(arrayAccessMatcher.group(2), memory), memory);
        }
        Matcher fieldAccessMatcher = FIELD_ACCESS_PATTERN.matcher(cleanExpression);
        if (fieldAccessMatcher.matches()) {
            return readField(fieldAccessMatcher.group(1), fieldAccessMatcher.group(2), memory);
        }

        return evaluateArithmetic(cleanExpression, memory);
    }

    private String stripOuterParentheses(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")") && enclosesWholeExpression(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private boolean enclosesWholeExpression(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0 && i < expression.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private int findTopLevelOperator(String expression, String operators) {
        int depth = 0;
        for (int i = expression.length() - 1; i >= 0; i--) {
            char current = expression.charAt(i);
            if (current == ')' || current == ']' || current == '}') {
                depth++;
            } else if (current == '(' || current == '[' || current == '{') {
                depth--;
            } else if (depth == 0 && operators.indexOf(current) >= 0 && !isUnaryOperator(expression, i)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isUnaryOperator(String expression, int operatorIndex) {
        if (operatorIndex == 0) {
            return true;
        }
        char previous = expression.charAt(operatorIndex - 1);
        return previous == '(' || previous == '[' || previous == '{'
                || previous == '+' || previous == '-' || previous == '*' || previous == '/';
    }

    private long allocateObject(String className, Map<String, Object> memory) {
        long objectId = OBJECT_ID_COUNTER.getAndIncrement();
        RuntimeObject instance = new RuntimeObject(objectId, className, initializeFields(className, memory), List.of());
        heap.put(objectId, instance);
        return objectId;
    }

    private long allocateSizedArray(String primitiveType, Object sizeValue) {
        int size = asInt(sizeValue);
        if (size < 0) {
            throw new VirtualExecutionException("NegativeArraySizeException: " + size);
        }

        List<Object> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            values.add(defaultArrayValue(primitiveType));
        }

        long objectId = OBJECT_ID_COUNTER.getAndIncrement();
        heap.put(objectId, new RuntimeObject(objectId, primitiveType + "[]", new LinkedHashMap<>(), values));
        return objectId;
    }

    private long allocateInitializedArray(String primitiveType, String initializerBody, Map<String, Object> memory) {
        List<Object> values = new ArrayList<>();
        if (!initializerBody.isBlank()) {
            for (String valueExpression : splitTopLevelComma(initializerBody)) {
                values.add(evaluateValue(valueExpression.trim(), memory));
            }
        }

        long objectId = OBJECT_ID_COUNTER.getAndIncrement();
        heap.put(objectId, new RuntimeObject(objectId, primitiveType + "[]", new LinkedHashMap<>(), values));
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
        if (value instanceof String stringValue && (
                OBJECT_CREATION_PATTERN.matcher(stringValue).matches()
                        || ARRAY_CREATION_INITIALIZER_PATTERN.matcher(stringValue).matches()
                        || ARRAY_CREATION_SIZED_PATTERN.matcher(stringValue).matches()
        )) {
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
        if ("length".equals(fieldName)) {
            Long pointer = resolvePointer(receiver, memory);
            RuntimeObject instance = pointer == null ? null : heap.get(pointer);
            if (instance != null && instance.className().endsWith("[]")) {
                return instance.values().size();
            }
        }

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

    private void writeArrayElement(String arrayName, Object indexValue, Object value, Map<String, Object> memory) {
        RuntimeObject array = resolveArray(arrayName, memory);
        int index = checkedArrayIndex(array, indexValue);
        array.values().set(index, value);
    }

    private Object readArrayElement(String arrayName, Object indexValue, Map<String, Object> memory) {
        RuntimeObject array = resolveArray(arrayName, memory);
        int index = checkedArrayIndex(array, indexValue);
        return array.values().get(index);
    }

    private int readArrayLength(String arrayName, Map<String, Object> memory) {
        return resolveArray(arrayName, memory).values().size();
    }

    private RuntimeObject resolveArray(String arrayName, Map<String, Object> memory) {
        Long pointer = resolvePointer(arrayName, memory);
        RuntimeObject instance = pointer == null ? null : heap.get(pointer);
        if (instance == null || !instance.className().endsWith("[]")) {
            throw new VirtualExecutionException(arrayName + " is not an array.");
        }
        return instance;
    }

    private int checkedArrayIndex(RuntimeObject array, Object indexValue) {
        int index = asInt(indexValue);
        if (index < 0 || index >= array.values().size()) {
            throw new VirtualExecutionException(
                    "ArrayIndexOutOfBoundsException: Index " + index + " out of bounds for length " + array.values().size()
            );
        }
        return index;
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
                "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\[[^\\]]+]|[A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z_$][A-Za-z0-9_$]*|[A-Za-z_$][A-Za-z0-9_$]*|-?\\d+|[+\\-*/]"
        ).matcher(expression);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private int resolveInteger(String token, Map<String, Object> memory) {
        Matcher arrayLengthMatcher = ARRAY_LENGTH_PATTERN.matcher(token);
        if (arrayLengthMatcher.matches()) {
            return readArrayLength(arrayLengthMatcher.group(1), memory);
        }

        Matcher arrayAccessMatcher = ARRAY_ACCESS_PATTERN.matcher(token);
        if (arrayAccessMatcher.matches()) {
            return asInt(readArrayElement(arrayAccessMatcher.group(1), evaluateValue(arrayAccessMatcher.group(2), memory), memory));
        }

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

    private Object evaluateIntrinsic(String intrinsicName, String argumentsSource, Map<String, Object> memory) {
        List<Object> arguments = new ArrayList<>();
        if (!argumentsSource.isBlank()) {
            for (String argument : splitTopLevelComma(argumentsSource)) {
                arguments.add(evaluateValue(argument.trim(), memory));
            }
        }

        return switch (intrinsicName) {
            case "Math.max" -> Math.max(asInt(arguments.get(0)), asInt(arguments.get(1)));
            case "Math.min" -> Math.min(asInt(arguments.get(0)), asInt(arguments.get(1)));
            case "Math.abs" -> Math.abs(asInt(arguments.get(0)));
            case "Math.pow" -> Math.pow(asDouble(arguments.get(0)), asDouble(arguments.get(1)));
            case "String.valueOf" -> String.valueOf(arguments.isEmpty() ? null : arguments.get(0));
            default -> 0;
        };
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? 1 : 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private Object defaultArrayValue(String primitiveType) {
        return switch (primitiveType) {
            case "boolean" -> false;
            case "float", "double" -> 0.0;
            case "char" -> '\0';
            default -> 0;
        };
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
            snapshot.add(new ObjectInstance(instance.id(), instance.className(), List.copyOf(instance.values())));
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
        for (Object elementValue : instance.values()) {
            markReachable(elementValue, reachable);
        }
    }

    private List<String> splitTopLevelComma(String source) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(source.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(source.substring(start));
        return parts;
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

    private record RuntimeObject(long id, String className, Map<String, Object> fields, List<Object> values) {
    }
}
