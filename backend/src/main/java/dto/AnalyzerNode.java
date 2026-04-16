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
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String type;
        private String label;
        private int line = -1;
        private boolean isError;
        private boolean isCall;
        private String callTarget;
        private String callReceiver;
        private String callMethodName;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder line(int line) {
            this.line = line;
            return this;
        }

        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }

        public Builder isCall(boolean isCall) {
            this.isCall = isCall;
            return this;
        }

        public Builder callTarget(String callTarget) {
            this.callTarget = callTarget;
            return this;
        }

        public Builder callReceiver(String callReceiver) {
            this.callReceiver = callReceiver;
            return this;
        }

        public Builder callMethodName(String callMethodName) {
            this.callMethodName = callMethodName;
            return this;
        }

        public AnalyzerNode build() {
            return new AnalyzerNode(
                    id,
                    type,
                    label,
                    line,
                    isError,
                    isCall,
                    callTarget,
                    callReceiver,
                    callMethodName
            );
        }
    }
}
