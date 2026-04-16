package dto;

import java.util.List;

public record AnalyzeResponse(
        GraphDTO graph,
        List<ExecutionStep> executionTrace
) {
}
