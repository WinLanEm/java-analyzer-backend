package dto;

import java.util.List;
import java.util.Map;

public record ExecutionStep(
        int step,
        String methodSignature,
        String activeNodeId,
        Map<String, Object> memory,
        List<String> callStack,
        List<ObjectInstance> heap,
        CurrentContextDTO currentContext
) {
}
