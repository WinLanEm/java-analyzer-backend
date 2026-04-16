import actions.BuildCfgAction;
import actions.SimulateExecutionAction;
import com.sun.net.httpserver.HttpServer;
import controllers.AnalyzeController;
import services.AnalyzerService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class Application {
    private static final int PORT = 8080;

    private Application() {
    }

    public static void main(String[] args) throws IOException {
        BuildCfgAction buildCfgAction = new BuildCfgAction();
        SimulateExecutionAction simulateExecutionAction = new SimulateExecutionAction();
        AnalyzerService analyzerService = new AnalyzerService(buildCfgAction, simulateExecutionAction);
        AnalyzeController analyzeController = new AnalyzeController(analyzerService);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/analyze", analyzeController);
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
        server.start();

        System.out.println("Code Analyzer server started on http://localhost:" + PORT);
    }
}
