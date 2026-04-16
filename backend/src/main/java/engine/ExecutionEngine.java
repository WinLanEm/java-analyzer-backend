package engine;

import dto.AnalyzerEdge;
import dto.AnalyzerNode;
import dto.CurrentContextDTO;
import dto.ExecutionStep;
import dto.GraphDTO;
import evaluator.ExpressionEvaluator;
import memory.ExecutionContext;
import memory.HeapManager;
import memory.RuntimeObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static constants.SimulationConstants.MAX_STEPS;
import static constants.SimulationConstants.RUNTIME_ERROR_NODE_ID;

public final class ExecutionEngine {
    private final HeapManager heapManager = new HeapManager();
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator(heapManager);

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
        heapManager.reset(graphs);

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

            heapManager.collectGarbage(callStack);
            trace.add(new ExecutionStep(
                    step,
                    context.methodSignature(),
                    currentNode.id(),
                    snapshotMemory(context.localVariables()),
                    callStackView(callStack),
                    heapManager.snapshotHeap(),
                    currentContext(graphs.get(context.methodSignature()))
            ));

            try {
                if (isThrowNode(currentNode)) {
                    context.markUncaughtException();
                }

                String callTarget = resolveCallTarget(currentNode, context.localVariables(), graphs);
                if (currentNode.isCall() && callTarget != null) {
                    enterMethodCall(currentNode, callTarget, context, graphs, edgesByMethod, callStack);
                    heapManager.collectGarbage(callStack);
                    step++;
                    continue;
                }

                if (isReturnNode(currentNode)) {
                    Object returnValue = evaluator.evaluateReturn(currentNode.label(), context.localVariables());
                    callStack.pop();
                    if (!callStack.isEmpty() && context.returnTargetVariable() != null) {
                        callStack.peek().localVariables().put(context.returnTargetVariable(), returnValue);
                    }
                    heapManager.collectGarbage(callStack);
                    step++;
                    continue;
                }

                evaluator.executeNode(currentNode.type(), currentNode.label(), context.localVariables());

                AnalyzerEdge nextEdge = chooseNextEdge(
                        currentNode,
                        edgesByMethod.getOrDefault(context.methodSignature(), Map.of())
                                .getOrDefault(currentNode.id(), List.of()),
                        context.localVariables()
                );
                context.setActiveNodeId(nextEdge == null ? null : nextEdge.target());
            } catch (VirtualExecutionException exception) {
                context.localVariables().put("__error", exception.getMessage());
                heapManager.collectGarbage(callStack);
                trace.add(new ExecutionStep(
                        step + 1,
                        context.methodSignature(),
                        RUNTIME_ERROR_NODE_ID,
                        snapshotMemory(context.localVariables()),
                        callStackView(callStack),
                        heapManager.snapshotHeap(),
                        currentContext(graphs.get(context.methodSignature()))
                ));
                break;
            }

            if (context.activeNodeId() == null && !callStack.isEmpty() && callStack.peek() == context) {
                popCompletedContext(callStack, graphs);
            }

            heapManager.collectGarbage(callStack);
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

    private void enterMethodCall(
            AnalyzerNode callNode,
            String callTarget,
            ExecutionContext callerContext,
            Map<String, GraphDTO> graphs,
            Map<String, Map<String, List<AnalyzerEdge>>> edgesByMethod,
            Deque<ExecutionContext> callStack
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
                evaluator.extractAssignmentTarget(callNode.label())
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

            RuntimeObject instance = heapManager.get(pointer);
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
        return evaluator.evaluateCallArguments(callNode.label(), methodName, memory);
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

        String expectedLabel = evaluator.evaluateCondition(currentNode.label(), memory) ? "true" : "false";
        for (AnalyzerEdge edge : outgoingEdges) {
            if (expectedLabel.equals(edge.label())) {
                return edge;
            }
        }

        return null;
    }

    private boolean isReturnNode(AnalyzerNode node) {
        return "action".equals(node.type()) && cleanStatement(node.label()).startsWith("return");
    }

    private boolean isThrowNode(AnalyzerNode node) {
        return "action".equals(node.type()) && cleanStatement(node.label()).startsWith("throw");
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

    private List<String> callStackView(Deque<ExecutionContext> callStack) {
        return callStack.stream()
                .map(ExecutionContext::methodSignature)
                .toList();
    }
}
