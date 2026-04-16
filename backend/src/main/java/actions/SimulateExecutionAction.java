package actions;

import dto.AnalyzerEdge;
import dto.AnalyzerNode;
import dto.ExecutionStep;
import dto.GraphDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimulateExecutionAction {
    private static final String RUNTIME_ERROR_NODE_ID = "runtime-error";
    private static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "^(?:final\\s+)?(?:byte|short|int|long|float|double|boolean|char|String|var)\\s+(.+)$"
    );
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+)$"
    );
    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$"
    );
    private static final int MAX_STEPS = 10_000;

    public List<ExecutionStep> execute(GraphDTO graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            return List.of();
        }

        Map<String, AnalyzerNode> nodesById = indexNodes(graph.nodes());
        Map<String, List<AnalyzerEdge>> edgesBySource = indexEdges(graph.edges());
        Map<String, Object> memory = new LinkedHashMap<>();
        List<ExecutionStep> trace = new ArrayList<>();

        AnalyzerNode currentNode = graph.nodes().getFirst();
        int step = 1;

        while (currentNode != null && step <= MAX_STEPS) {
            trace.add(new ExecutionStep(step, currentNode.id(), snapshotMemory(memory)));

            if ("action".equals(currentNode.type())) {
                try {
                    executeAction(currentNode.label(), memory);
                } catch (VirtualExecutionException exception) {
                    memory.put("__error", exception.getMessage());
                    trace.add(new ExecutionStep(step + 1, RUNTIME_ERROR_NODE_ID, snapshotMemory(memory)));
                    break;
                }
            }

            List<AnalyzerEdge> outgoingEdges = edgesBySource.getOrDefault(currentNode.id(), List.of());
            if (outgoingEdges.isEmpty()) {
                break;
            }

            AnalyzerEdge nextEdge = chooseNextEdge(currentNode, outgoingEdges, memory);
            currentNode = nextEdge == null ? null : nodesById.get(nextEdge.target());
            step++;
        }

        return List.copyOf(trace);
    }

    private Map<String, AnalyzerNode> indexNodes(List<AnalyzerNode> nodes) {
        Map<String, AnalyzerNode> nodesById = new LinkedHashMap<>();
        for (AnalyzerNode node : nodes) {
            nodesById.put(node.id(), node);
        }
        return nodesById;
    }

    private Map<String, List<AnalyzerEdge>> indexEdges(List<AnalyzerEdge> edges) {
        Map<String, List<AnalyzerEdge>> edgesBySource = new LinkedHashMap<>();
        if (edges == null) {
            return edgesBySource;
        }

        for (AnalyzerEdge edge : edges) {
            edgesBySource.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
        }
        return edgesBySource;
    }

    private AnalyzerEdge chooseNextEdge(
            AnalyzerNode currentNode,
            List<AnalyzerEdge> outgoingEdges,
            Map<String, Object> memory
    ) {
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

    private void executeAction(String label, Map<String, Object> memory) {
        String statement = cleanStatement(label);
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

        return evaluateArithmetic(cleanExpression, memory);
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
        Matcher matcher = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|-?\\d+|[+\\-*/]").matcher(expression);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private int resolveInteger(String token, Map<String, Object> memory) {
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

    private static final class VirtualExecutionException extends RuntimeException {
        private VirtualExecutionException(String message) {
            super(message);
        }
    }
}
