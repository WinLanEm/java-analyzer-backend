package dto;

import java.util.List;
import java.util.Map;

public record GraphDTO(
        List<AnalyzerNode> nodes,
        List<AnalyzerEdge> edges,
        List<String> parameters,
        String className,
        String methodName,
        Map<String, Object> classFields
) {
}
