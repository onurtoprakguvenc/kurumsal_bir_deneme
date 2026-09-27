package org.yazi.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yazi.gateway.GatewayException.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Gemini implementation of {@link ModelGateway}. Every request, plain or structured, goes through
 * {@code streamGenerateContent?alt=sse}, so both kinds share one code path for cancellation, finish reasons and
 * usage accounting.
 *
 * <p>Consolidates the four HTTP stacks of the old projects (HttpURLConnection + Gson, java.net.http blocking,
 * java.net.http SSE, and none). The key always travels in the {@code x-goog-api-key} header, never in the URL,
 * and never appears in exception messages.</p>
 *
 * <p>Thread-safe and stateless: one instance serves the whole application.</p>
 */
public final class GeminiGateway implements ModelGateway {

    public static final URI DEFAULT_BASE_URI = URI.create("https://generativelanguage.googleapis.com/v1beta/");

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int ERROR_BODY_BYTES = 8_192;
    private static final int ERROR_TEXT_CHARS = 500;

    private final String apiKey;
    private final URI baseUri;
    private final HttpClient http;
    private final Duration responseTimeout;
    private final ObjectMapper json;

    public GeminiGateway(String apiKey) {
        this(apiKey, DEFAULT_BASE_URI,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                Duration.ofSeconds(60));
    }

    /**
     * @param responseTimeout how long to wait for the response headers; once streaming has started, only
     *                        cancellation ends a request
     */
    public GeminiGateway(String apiKey, URI baseUri, HttpClient http, Duration responseTimeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("An API key is required.");
        }
        this.apiKey = apiKey.strip();
        this.baseUri = baseUri;
        this.http = http;
        this.responseTimeout = responseTimeout;
        // Lenient reading, as prompt_gelistirme had: models occasionally emit raw control characters or
        // over-escaped characters inside JSON strings. Valid JSON parses identically either way.
        this.json = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .build();
    }

    @Override
    public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken cancellation) throws GatewayException {
        TokenSink target = (sink == null) ? TokenSink.DISCARD : sink;
        StringBuilder text = new StringBuilder();
        Outcome outcome = exchange(call, payload(call, null), cancellation, fragment -> {
            text.append(fragment);
            target.accept(fragment);
        });
        return new StreamResult(text.toString(), outcome.finishReason(), outcome.usage());
    }

    @Override
    public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode responseSchema,
                                                  CancellationToken cancellation) throws GatewayException {
        StringBuilder text = new StringBuilder();
        Outcome outcome = exchange(call, payload(call, responseSchema), cancellation, text::append);

        if (outcome.finishReason() == FinishReason.MAX_TOKENS) {
            throw new GatewayException(Kind.TRUNCATED, "Structured output for " + type.getSimpleName()
                    + " was cut off at maxOutputTokens=" + call.maxOutputTokens() + ".");
        }
        String body = stripFences(text.toString());
        if (body.isBlank()) {
            throw new GatewayException(Kind.MALFORMED, "Model returned no JSON for " + type.getSimpleName() + ".");
        }
        try {
            return new Structured<>(json.readValue(body, type), outcome.usage());
        } catch (JsonProcessingException e) {
            throw new GatewayException(Kind.MALFORMED, "Model JSON does not match " + type.getSimpleName() + ": "
                    + e.getOriginalMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------------------------------------

    private record Outcome(FinishReason finishReason, Usage usage) {}

    private Outcome exchange(ModelCall call, ObjectNode payload, CancellationToken cancellation,
                             Consumer<String> onText) throws GatewayException {
        CancellationToken token = (cancellation == null) ? CancellationToken.none() : cancellation;
        if (token.isCancelled()) {
            throw cancelled();
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint(call.model()))
                .timeout(responseTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(toBytes(payload)))
                .build();

        HttpResponse<InputStream> response = awaitResponse(request, token);

        Thread reader = Thread.currentThread();
        try (InputStream body = response.body();
             CancellationToken.Registration ignored = token.onCancel(() -> {
                 closeQuietly(body);
                 reader.interrupt();   // unblocks a read that is waiting for the next network packet
             })) {
            if (response.statusCode() != 200) {
                throw httpFailure(response.statusCode(), body, response.headers());
            }
            StreamState state = new StreamState(onText);
            SseEventReader.read(body, data -> {
                if (token.isCancelled()) {
                    throw cancelled();
                }
                state.accept(parseEvent(data));
            });
            if (token.isCancelled()) {
                throw cancelled();
            }
            return state.finish();
        } catch (IOException e) {
            if (token.isCancelled()) {
                throw cancelled();
            }
            throw new GatewayException(Kind.NETWORK, "Stream interrupted: " + e.getMessage(), e);
        } finally {
            if (token.isCancelled()) {
                Thread.interrupted();   // clear the interrupt raised by our own cancel callback
            }
        }
    }

    private HttpResponse<InputStream> awaitResponse(HttpRequest request, CancellationToken token)
            throws GatewayException {
        CompletableFuture<HttpResponse<InputStream>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try (CancellationToken.Registration ignored = token.onCancel(() -> pending.cancel(true))) {
            return pending.get();
        } catch (CancellationException e) {
            throw cancelled();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new GatewayException(Kind.NETWORK, "No response within " + responseTimeout.toSeconds() + "s.", cause);
            }
            throw new GatewayException(Kind.NETWORK, "Could not reach the model API: " + cause.getMessage(), cause);
        }
    }

    private URI endpoint(String model) {
        if (!MODEL_NAME.matcher(model).matches()) {
            throw new IllegalArgumentException("Invalid model name: " + model);
        }
        return baseUri.resolve("models/" + model + ":streamGenerateContent?alt=sse");
    }

    // ------------------------------------------------------------------------------------------------
    // Request payload
    // ------------------------------------------------------------------------------------------------

    ObjectNode payload(ModelCall call, JsonNode responseSchema) {
        ObjectNode root = json.createObjectNode();
        if (!call.system().isBlank()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", call.system());
        }

        // Exactly one user turn. There is no API here for adding more.
        ObjectNode turn = root.putArray("contents").addObject();
        turn.put("role", "user");
        ArrayNode parts = turn.putArray("parts");
        for (InlineImage image : call.images()) {
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", image.mimeType());
            inline.put("data", Base64.getEncoder().encodeToString(image.data()));
        }
        parts.addObject().put("text", call.user());

        ObjectNode generation = root.putObject("generationConfig");
        generation.put("temperature", call.temperature());
        generation.put("maxOutputTokens", call.maxOutputTokens());
        if (call.thinkingBudget() != null) {
            generation.putObject("thinkingConfig").put("thinkingBudget", call.thinkingBudget());
        }
        if (responseSchema != null) {
            generation.put("responseMimeType", "application/json");
            generation.set("responseSchema", responseSchema);
        }
        return root;
    }

    private byte[] toBytes(ObjectNode payload) {
        try {
            return json.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise request payload", e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------------------------------------

    private record Event(String text, String finishReason, Usage usage, String blockReason, String error) {}

    private Event parseEvent(String data) throws GatewayException {
        JsonNode root;
        try {
            root = json.readTree(data);
        } catch (JsonProcessingException e) {
            throw new GatewayException(Kind.MALFORMED, "Unreadable stream event: " + shorten(data), e);
        }
        JsonNode candidate = root.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue;   // thinking summaries never reach the writer's buffer
            }
            JsonNode t = part.get("text");
            if (t != null && t.isTextual()) {
                text.append(t.asText());
            }
        }
        return new Event(
                text.toString(),
                textOrNull(candidate.path("finishReason")),
                root.has("usageMetadata") ? Usage.from(root.get("usageMetadata")) : null,
                textOrNull(root.path("promptFeedback").path("blockReason")),
                textOrNull(root.path("error").path("message")));
    }

    /** Folds stream events into text, finish reason and the last reported usage. */
    private static final class StreamState {
        private final Consumer<String> onText;
        private boolean sawEvent;
        private String rawFinishReason;
        private Usage usage = Usage.EMPTY;

        StreamState(Consumer<String> onText) {
            this.onText = onText;
        }

        void accept(Event event) throws GatewayException {
            sawEvent = true;
            if (event.error() != null) {
                throw new GatewayException(Kind.HTTP, "API error: " + shorten(event.error()));
            }
            if (event.blockReason() != null) {
                throw new GatewayException(Kind.BLOCKED, "Prompt blocked by the provider (" + event.blockReason() + ").");
            }
            if (!event.text().isEmpty()) {
                onText.accept(event.text());
            }
            if (event.usage() != null) {
                usage = event.usage();
            }
            if (event.finishReason() != null) {
                rawFinishReason = event.finishReason();
            }
        }

        Outcome finish() throws GatewayException {
            if (!sawEvent) {
                throw new GatewayException(Kind.MALFORMED, "Stream closed without any data.");
            }
            FinishReason reason = FinishReason.parse(rawFinishReason);
            if (reason == FinishReason.BLOCKED) {
                throw new GatewayException(Kind.BLOCKED, "Output stopped by the provider (" + rawFinishReason + ").");
            }
            return new Outcome(reason, usage);
        }
    }

    private GatewayException httpFailure(int status, InputStream body, HttpHeaders headers) {
        String detail = readErrorDetail(body);
        return switch (status) {
            case 401, 403 -> new GatewayException(Kind.AUTH, "API key rejected (HTTP " + status + "): " + detail,
                    status, null, null);
            case 429 -> new GatewayException(Kind.RATE_LIMITED, "Rate limit or quota reached: " + detail,
                    status, retryAfter(headers), null);
            default -> new GatewayException(Kind.HTTP, "HTTP " + status + ": " + detail, status, null, null);
        };
    }

    private String readErrorDetail(InputStream body) {
        try {
            String raw = new String(body.readNBytes(ERROR_BODY_BYTES), StandardCharsets.UTF_8);
            try {
                String message = textOrNull(json.readTree(raw).path("error").path("message"));
                return shorten(message != null ? message : raw);
            } catch (JsonProcessingException notJson) {
                return shorten(raw);
            }
        } catch (IOException e) {
            return "(error body unreadable)";
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After")
                .flatMap(v -> {
                    try {
                        return Optional.of(Duration.ofSeconds(Long.parseLong(v.strip())));
                    } catch (NumberFormatException e) {
                        return Optional.empty();   // HTTP-date form; the caller falls back to its own backoff
                    }
                })
                .orElse(null);
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static GatewayException cancelled() {
        return new GatewayException(Kind.CANCELLED, "Request cancelled.");
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : null;
    }

    private static String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[\\w-]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return s;
    }

    private static String shorten(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return (flat.length() <= ERROR_TEXT_CHARS) ? flat : flat.substring(0, ERROR_TEXT_CHARS) + "…";
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing only to unblock the reader
        }
    }
}
