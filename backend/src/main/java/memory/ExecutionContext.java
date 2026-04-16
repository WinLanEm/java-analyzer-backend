package memory;

import java.util.Map;

public final class ExecutionContext {
    private final String methodSignature;
    private String activeNodeId;
    private final Map<String, Object> localVariables;
    private final String returnTargetVariable;
    private boolean uncaughtException;

    public ExecutionContext(
            String methodSignature,
            String activeNodeId,
            Map<String, Object> localVariables,
            String returnTargetVariable
    ) {
        this.methodSignature = methodSignature;
        this.activeNodeId = activeNodeId;
        this.localVariables = localVariables;
        this.returnTargetVariable = returnTargetVariable;
    }

    public String methodSignature() {
        return methodSignature;
    }

    public String activeNodeId() {
        return activeNodeId;
    }

    public void setActiveNodeId(String activeNodeId) {
        this.activeNodeId = activeNodeId;
    }

    public Map<String, Object> localVariables() {
        return localVariables;
    }

    public String returnTargetVariable() {
        return returnTargetVariable;
    }

    public boolean isUncaughtException() {
        return uncaughtException;
    }

    public void markUncaughtException() {
        uncaughtException = true;
    }
}
