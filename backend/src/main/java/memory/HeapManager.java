package memory;

import dto.GraphDTO;
import dto.ObjectInstance;
import engine.VirtualExecutionException;
import evaluator.ExpressionEvaluator;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class HeapManager {
    private static final AtomicLong OBJECT_ID_COUNTER = new AtomicLong(1);

    private Map<Long, RuntimeObject> heap = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> classFieldDefaults = new LinkedHashMap<>();

    public void reset(Map<String, GraphDTO> graphs) {
        heap = new LinkedHashMap<>();
        classFieldDefaults = indexFields(graphs);
    }

    public RuntimeObject get(long objectId) {
        return heap.get(objectId);
    }

    public long allocateObject(String className, Map<String, Object> memory, ExpressionEvaluator evaluator) {
        long objectId = OBJECT_ID_COUNTER.getAndIncrement();
        RuntimeObject instance = new RuntimeObject(objectId, className, initializeFields(className, memory, evaluator), List.of());
        heap.put(objectId, instance);
        return objectId;
    }

    public long allocateSizedArray(String primitiveType, Object sizeValue, ExpressionEvaluator evaluator) {
        int size = evaluator.asInt(sizeValue);
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

    public long allocateInitializedArray(
            String primitiveType,
            String initializerBody,
            Map<String, Object> memory,
            ExpressionEvaluator evaluator
    ) {
        List<Object> values = new ArrayList<>();
        if (!initializerBody.isBlank()) {
            for (String valueExpression : evaluator.splitTopLevelComma(initializerBody)) {
                values.add(evaluator.evaluateExpression(valueExpression.trim(), memory));
            }
        }

        long objectId = OBJECT_ID_COUNTER.getAndIncrement();
        heap.put(objectId, new RuntimeObject(objectId, primitiveType + "[]", new LinkedHashMap<>(), values));
        return objectId;
    }

    public List<ObjectInstance> snapshotHeap() {
        List<ObjectInstance> snapshot = new ArrayList<>();
        for (RuntimeObject instance : heap.values()) {
            snapshot.add(new ObjectInstance(
                    instance.id(),
                    instance.className(),
                    List.copyOf(instance.values()),
                    Map.copyOf(instance.fields())
            ));
        }
        return List.copyOf(snapshot);
    }

    public void collectGarbage(Deque<ExecutionContext> callStack) {
        Set<Long> reachable = new HashSet<>();
        for (ExecutionContext context : callStack) {
            for (Object value : context.localVariables().values()) {
                markReachable(value, reachable);
            }
        }
        heap.keySet().removeIf(objectId -> !reachable.contains(objectId));
    }

    private Map<String, Map<String, Object>> indexFields(Map<String, GraphDTO> graphs) {
        Map<String, Map<String, Object>> fieldsByClass = new LinkedHashMap<>();
        for (GraphDTO graph : graphs.values()) {
            fieldsByClass.putIfAbsent(graph.className(), graph.classFields());
        }
        return fieldsByClass;
    }

    private Map<String, Object> initializeFields(
            String className,
            Map<String, Object> memory,
            ExpressionEvaluator evaluator
    ) {
        Map<String, Object> initializedFields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : classFields(className).entrySet()) {
            initializedFields.put(field.getKey(), initializeFieldValue(field.getValue(), memory, evaluator));
        }
        return initializedFields;
    }

    private Object initializeFieldValue(Object value, Map<String, Object> memory, ExpressionEvaluator evaluator) {
        if (value instanceof String stringValue && evaluator.isAllocationExpression(stringValue)) {
            return evaluator.evaluateExpression(stringValue, memory);
        }
        return value;
    }

    private Map<String, Object> classFields(String className) {
        return classFieldDefaults.getOrDefault(className, Map.of());
    }

    private Object defaultArrayValue(String primitiveType) {
        return switch (primitiveType) {
            case "boolean" -> false;
            case "float", "double" -> 0.0;
            case "char" -> '\0';
            default -> 0;
        };
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
}
