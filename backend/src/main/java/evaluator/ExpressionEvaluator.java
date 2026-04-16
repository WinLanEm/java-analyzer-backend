package evaluator;

import engine.VirtualExecutionException;
import memory.HeapManager;
import memory.RuntimeObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static constants.SimulationConstants.ARITHMETIC_TOKEN_PATTERN;
import static constants.SimulationConstants.ARRAY_ACCESS_PATTERN;
import static constants.SimulationConstants.ARRAY_ASSIGNMENT_PATTERN;
import static constants.SimulationConstants.ARRAY_CREATION_INITIALIZER_PATTERN;
import static constants.SimulationConstants.ARRAY_CREATION_SIZED_PATTERN;
import static constants.SimulationConstants.ARRAY_LENGTH_PATTERN;
import static constants.SimulationConstants.ASSIGNMENT_PATTERN;
import static constants.SimulationConstants.COMPARISON_PATTERN;
import static constants.SimulationConstants.COMPOUND_ASSIGNMENT_PATTERN;
import static constants.SimulationConstants.DECLARATION_PATTERN;
import static constants.SimulationConstants.FIELD_ACCESS_PATTERN;
import static constants.SimulationConstants.FIELD_ASSIGNMENT_PATTERN;
import static constants.SimulationConstants.INTRINSIC_CALL_PATTERN;
import static constants.SimulationConstants.OBJECT_CREATION_PATTERN;
import static constants.SimulationConstants.POSTFIX_UPDATE_PATTERN;
import static constants.SimulationConstants.PREFIX_UPDATE_PATTERN;

public final class ExpressionEvaluator {
    private final HeapManager heapManager;

    public ExpressionEvaluator(HeapManager heapManager) {
        this.heapManager = heapManager;
    }

    public void executeNode(String nodeType, String label, Map<String, Object> memory) {
        if (!"action".equals(nodeType)) {
            return;
        }

        executeAction(label, memory);
    }

    public boolean evaluateCondition(String expression, Map<String, Object> memory) {
        String cleanExpression = cleanStatement(expression);

        Matcher comparisonMatcher = COMPARISON_PATTERN.matcher(cleanExpression);
        if (comparisonMatcher.matches()) {
            Object left = evaluateExpression(comparisonMatcher.group(1), memory);
            Object right = evaluateExpression(comparisonMatcher.group(3), memory);
            return compare(left, comparisonMatcher.group(2), right);
        }

        Object value = evaluateExpression(cleanExpression, memory);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    public Object evaluateReturn(String label, Map<String, Object> memory) {
        String expression = cleanStatement(label).replaceFirst("^return\\s*", "");
        return expression.isBlank() ? null : evaluateExpression(expression, memory);
    }

    public List<Object> evaluateCallArguments(String callLabel, String methodName, Map<String, Object> memory) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\((.*)\\)").matcher(callLabel);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            return List.of();
        }

        List<Object> arguments = new ArrayList<>();
        for (String argument : splitTopLevelComma(matcher.group(1))) {
            arguments.add(evaluateExpression(argument.trim(), memory));
        }
        return arguments;
    }

    public String extractAssignmentTarget(String label) {
        String statement = cleanStatement(label);
        Matcher declarationMatcher = DECLARATION_PATTERN.matcher(statement);
        if (declarationMatcher.matches()) {
            statement = declarationMatcher.group(1);
        }

        Matcher assignmentMatcher = ASSIGNMENT_PATTERN.matcher(statement);
        return assignmentMatcher.matches() ? assignmentMatcher.group(1) : null;
    }

    public Object evaluateExpression(String expression, Map<String, Object> memory) {
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
        if (isUnaryUpdateExpression(cleanExpression)) {
            return evaluateUnaryUpdateExpression(cleanExpression, memory);
        }
        Matcher intrinsicMatcher = INTRINSIC_CALL_PATTERN.matcher(cleanExpression);
        if (intrinsicMatcher.matches()) {
            return evaluateIntrinsic(intrinsicMatcher.group(1), intrinsicMatcher.group(2), memory);
        }
        Matcher arrayInitializerMatcher = ARRAY_CREATION_INITIALIZER_PATTERN.matcher(cleanExpression);
        if (arrayInitializerMatcher.matches()) {
            return heapManager.allocateInitializedArray(arrayInitializerMatcher.group(1), arrayInitializerMatcher.group(2), memory, this);
        }
        Matcher sizedArrayMatcher = ARRAY_CREATION_SIZED_PATTERN.matcher(cleanExpression);
        if (sizedArrayMatcher.matches()) {
            return heapManager.allocateSizedArray(sizedArrayMatcher.group(1), evaluateExpression(sizedArrayMatcher.group(2), memory), this);
        }
        Matcher objectCreationMatcher = OBJECT_CREATION_PATTERN.matcher(cleanExpression);
        if (objectCreationMatcher.matches()) {
            return heapManager.allocateObject(objectCreationMatcher.group(1), memory, this);
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
            return readArrayElement(arrayAccessMatcher.group(1), evaluateExpression(arrayAccessMatcher.group(2), memory), memory);
        }
        Matcher fieldAccessMatcher = FIELD_ACCESS_PATTERN.matcher(cleanExpression);
        if (fieldAccessMatcher.matches()) {
            return readField(fieldAccessMatcher.group(1), fieldAccessMatcher.group(2), memory);
        }

        return evaluateArithmetic(cleanExpression, memory);
    }

    public boolean isAllocationExpression(String expression) {
        return OBJECT_CREATION_PATTERN.matcher(expression).matches()
                || ARRAY_CREATION_INITIALIZER_PATTERN.matcher(expression).matches()
                || ARRAY_CREATION_SIZED_PATTERN.matcher(expression).matches();
    }

    public int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? 1 : 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public List<String> splitTopLevelComma(String source) {
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

        if (isUnaryUpdateExpression(cleanStatement)) {
            evaluateUnaryUpdateExpression(cleanStatement, memory);
            return true;
        }

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

    private Object evaluateUnaryUpdateExpression(String expression, Map<String, Object> memory) {
        Matcher postfixUpdateMatcher = POSTFIX_UPDATE_PATTERN.matcher(expression);
        if (postfixUpdateMatcher.matches()) {
            return applyVariableUpdate(postfixUpdateMatcher.group(1), postfixUpdateMatcher.group(2), true, memory);
        }

        Matcher prefixUpdateMatcher = PREFIX_UPDATE_PATTERN.matcher(expression);
        if (prefixUpdateMatcher.matches()) {
            return applyVariableUpdate(prefixUpdateMatcher.group(2), prefixUpdateMatcher.group(1), false, memory);
        }

        return 0;
    }

    private boolean isUnaryUpdateExpression(String expression) {
        return POSTFIX_UPDATE_PATTERN.matcher(expression).matches()
                || PREFIX_UPDATE_PATTERN.matcher(expression).matches();
    }

    private Object applyVariableUpdate(
            String variableName,
            String operator,
            boolean returnPreviousValue,
            Map<String, Object> memory
    ) {
        int currentValue = asInt(evaluateExpression(variableName, memory));
        int nextValue = "++".equals(operator) ? currentValue + 1 : currentValue - 1;
        memory.put(variableName, nextValue);
        return returnPreviousValue ? currentValue : nextValue;
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
                evaluateExpression(argument.trim(), memory);
            }
        }
    }

    private void assignField(String receiver, String fieldName, Object value, Map<String, Object> memory) {
        Long pointer = resolvePointer(receiver, memory);
        if (pointer == null) {
            return;
        }

        RuntimeObject instance = heapManager.get(pointer);
        if (instance != null) {
            instance.fields().put(fieldName, value);
        }
    }

    private Object readField(String receiver, String fieldName, Map<String, Object> memory) {
        if ("length".equals(fieldName)) {
            Long pointer = resolvePointer(receiver, memory);
            RuntimeObject instance = pointer == null ? null : heapManager.get(pointer);
            if (instance != null && instance.className().endsWith("[]")) {
                return instance.values().size();
            }
        }

        Long pointer = resolvePointer(receiver, memory);
        if (pointer == null) {
            return 0;
        }

        RuntimeObject instance = heapManager.get(pointer);
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
        RuntimeObject instance = pointer == null ? null : heapManager.get(pointer);
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
        Matcher matcher = ARITHMETIC_TOKEN_PATTERN.matcher(expression);
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
            return asInt(readArrayElement(arrayAccessMatcher.group(1), evaluateExpression(arrayAccessMatcher.group(2), memory), memory));
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
                arguments.add(evaluateExpression(argument.trim(), memory));
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

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
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

    private String cleanStatement(String statement) {
        return statement == null ? "" : statement.trim().replaceAll(";$", "").trim();
    }
}
