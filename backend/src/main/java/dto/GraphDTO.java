package dto;

import java.util.List;

public record GraphDTO(
        List<AnalyzerNode> nodes,
        List<AnalyzerEdge> edges
) {
}
