package dto;

import java.util.List;
import java.util.Map;

public record AnalyzeResponse(
        Map<String, GraphDTO> graphs,
        List<ExecutionStep> executionTrace
) {
}
