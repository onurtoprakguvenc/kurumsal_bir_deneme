package org.yazi.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local stand-in for the Gemini streaming endpoint. Records the last request; responds with a scripted handler. */
final class FakeGemini implements AutoCloseable {

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange) throws Exception;
    }

    record Captured(String method, String path, String query, String apiKeyHeader, String body) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile Responder responder = exchange -> sse(exchange, "{}");
    private volatile Captured last;

    FakeGemini() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1beta/models/", exchange -> {
            try {
                last = new Captured(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getQuery(),
                        exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                responder.respond(exchange);
            } catch (Exception e) {
                // the client may have gone away (cancellation tests); nothing to report
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    void respondWith(Responder r) {
        this.responder = r;
    }

    Captured last() {
        return last;
    }

    GeminiGateway gateway() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new GeminiGateway("test-key", base, client, Duration.ofSeconds(5));
    }

    /** Writes each JSON payload as one SSE event and completes the response. */
    static void sse(HttpExchange exchange, String... events) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String event : events) {
                out.write(("data: " + event + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    static void status(HttpExchange exchange, int code, String body, String retryAfter) throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String textEvent(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(text) + "}]}}]}";
    }

    static String finishEvent(String text, String reason, int promptTokens, int outputTokens) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(text) + "}]},\"finishReason\":\"" + reason + "\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":" + promptTokens + ",\"candidatesTokenCount\":" + outputTokens
                + ",\"totalTokenCount\":" + (promptTokens + outputTokens) + "}}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
