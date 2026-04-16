package dto;

import java.util.Map;

public record ExecutionStep(
        int step,
        String activeNodeId,
        Map<String, Object> memory
) {
}
