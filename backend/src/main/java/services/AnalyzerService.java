package services;

import actions.BuildCfgAction;
import actions.SimulateExecutionAction;
import dto.AnalyzeResponse;
import dto.ExecutionStep;
import dto.GraphDTO;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnalyzerService {
    private final BuildCfgAction buildCfgAction;
    private final SimulateExecutionAction simulateExecutionAction;

    public AnalyzerService(BuildCfgAction buildCfgAction, SimulateExecutionAction simulateExecutionAction) {
        this.buildCfgAction = Objects.requireNonNull(buildCfgAction, "buildCfgAction must not be null");
        this.simulateExecutionAction = Objects.requireNonNull(
                simulateExecutionAction,
                "simulateExecutionAction must not be null"
        );
    }

    public AnalyzeResponse analyze(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code must not be blank.");
        }

        Map<String, GraphDTO> graphs = buildCfgAction.execute(code);
        List<ExecutionStep> executionTrace = simulateExecutionAction.execute(graphs);
        return new AnalyzeResponse(graphs, executionTrace);
    }
}
