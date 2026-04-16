package dto;

public record AnalyzerNode(
        String id,
        String type,
        String label,
        int line,
        boolean isError
) {
}
