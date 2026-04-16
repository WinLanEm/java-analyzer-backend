package dto;

public record AnalyzerNode(
        String id,
        String type,
        String label,
        int line,
        boolean isError,
        boolean isCall,
        String callTarget,
        String callReceiver,
        String callMethodName
) {
}
