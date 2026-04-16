package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ParseProblemException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dto.AnalyzeRequest;
import dto.AnalyzeResponse;
import services.AnalyzerService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class AnalyzeController implements HttpHandler {
    private final AnalyzerService analyzerService;
    private final ObjectMapper objectMapper;

    public AnalyzeController(AnalyzerService analyzerService) {
        this(analyzerService, new ObjectMapper());
    }

    public AnalyzeController(AnalyzerService analyzerService, ObjectMapper objectMapper) {
        this.analyzerService = Objects.requireNonNull(analyzerService, "analyzerService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Only POST is supported."));
            return;
        }

        try {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            AnalyzeRequest request = objectMapper.readValue(requestBody, AnalyzeRequest.class);
            AnalyzeResponse response = analyzerService.analyze(request.code());
            sendJson(exchange, 200, response);
        } catch (ParseProblemException exception) {
            sendJson(exchange, 400, Map.of("error", "Unable to parse Java code.", "details", exception.getMessage()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            sendJson(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            sendJson(exchange, 500, Map.of("error", "Internal server error."));
        }
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] responseBody = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, responseBody.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBody);
        }
    }
}
