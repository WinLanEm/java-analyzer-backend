package services;

import actions.BuildCfgAction;
import dto.AnalyzeResponse;
import dto.ExecutionStep;
import dto.GraphDTO;
import engine.ExecutionEngine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnalyzerService {
    private final BuildCfgAction buildCfgAction;
    private final ExecutionEngine executionEngine;

    public AnalyzerService(BuildCfgAction buildCfgAction, ExecutionEngine executionEngine) {
        this.buildCfgAction = Objects.requireNonNull(buildCfgAction, "buildCfgAction must not be null");
        this.executionEngine = Objects.requireNonNull(
                executionEngine,
                "executionEngine must not be null"
        );
    }

    public AnalyzeResponse analyze(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code must not be blank.");
        }

        Map<String, GraphDTO> graphs = buildCfgAction.execute(code);
        List<ExecutionStep> executionTrace = executionEngine.execute(graphs);
        return new AnalyzeResponse(graphs, executionTrace);
    }
}
