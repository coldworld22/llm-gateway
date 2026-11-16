package io.github.coldworld22.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake OpenAI-compatible provider for tests: it echoes the last message back, reports fixed
 * token usage and records every request body, so tests can check exactly what left the gateway.
 */
final class StubProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile boolean failing;

    StubProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (failing) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            JsonNode messages = JSON.readTree(body).get("messages");
            String last = messages.get(messages.size() - 1).get("content").asText();
            byte[] response = JSON.writeValueAsBytes(Map.of(
                    "id", "stub",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "message", Map.of("role", "assistant", "content", "You said: " + last),
                            "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 12, "completion_tokens", 8, "total_tokens", 20)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    void reset() {
        requests.clear();
        failing = false;
    }
}
