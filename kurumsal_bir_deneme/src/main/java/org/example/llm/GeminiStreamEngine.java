package org.example.llm;

import org.example.core.AnswerModel;
import org.example.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateless streaming client for {@code models/{model}:streamGenerateContent?alt=sse} on the Google Generative
 * Language API, built only on {@link HttpClient}.
 *
 * <p>Every call is self-contained: system instruction, the (at most three) ephemeral turns supplied by the
 * caller and the grounded user message. Text parts are forwarded as they arrive; "thought" parts are skipped.
 * Connect/header timeouts, an idle watchdog, bounded retries (429/5xx/network, only before the first token) and
 * prompt upstream cancellation when the sink throws are built in. The active model can be switched at any time;
 * a request uses the model that was active when it started.</p>
 */
public final class GeminiStreamEngine implements AnswerModel {

    private static final System.Logger LOG = System.getLogger(GeminiStreamEngine.class.getName());

    public static final URI DEFAULT_BASE_URI = URI.create("https://generativelanguage.googleapis.com/v1beta/models/");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 800;
    private static final int MAX_ERROR_BODY = 32 * 1024;
    /**
     * Transient server errors only. 429 is not retried: it means the key's quota or rate is exhausted, and immediate
     * retries only add calls the local quota shield never granted.
     */
    private static final Set<Integer> RETRYABLE = Set.of(500, 502, 503, 504);
    private static final Set<String> BLOCKING_REASONS =
            Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII");

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("gemini-idle-watchdog").daemon(true).factory());

    private final HttpClient http;
    private final String apiKey;
    private final String baseUri;
    private final Duration idleTimeout;
    private volatile ModelProfile profile;

    public GeminiStreamEngine(String apiKey, ModelProfile profile) {
        this(apiKey, profile, DEFAULT_BASE_URI, DEFAULT_IDLE_TIMEOUT);
    }

    public GeminiStreamEngine(String apiKey, ModelProfile profile, URI baseUri, Duration idleTimeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.apiKey = apiKey.strip();
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        String base = Objects.requireNonNull(baseUri, "baseUri must not be null").toString();
        this.baseUri = base.endsWith("/") ? base : base + "/";
        String scheme = baseUri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUri must be http(s): " + baseUri);
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopback(baseUri.getHost())) {
            // Kept working for existing proxy setups, but the API key travels in a request header in clear text.
            LOG.log(System.Logger.Level.WARNING, "Gemini base URL {0} is plain HTTP to another host: the API key is"
                    + " sent unencrypted over the network; use https", baseUri);
        }
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void select(ModelProfile next) {
        this.profile = Objects.requireNonNull(next, "profile must not be null");
    }

    public ModelProfile profile() {
        return profile;
    }

    @Override
    public String modelId() {
        return profile.id();
    }

    @Override
    public int contextCharBudget() {
        return profile.contextCharBudget();
    }

    @Override
    public AnswerStats stream(AnswerRequest request, TokenSink sink) throws AnswerException {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        ModelProfile active = profile;
        // String concatenation on purpose: URI.resolve would read "gemini-3.6-flash:" as a URI scheme.
        URI endpoint = URI.create(baseUri + active.id() + ":streamGenerateContent?alt=sse");
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(HEADERS_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body(request, active), StandardCharsets.UTF_8))
                .build();

        long started = System.nanoTime();
        long backoff = INITIAL_BACKOFF_MILLIS;
        AnswerException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<InputStream> response;
            try {
                response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpConnectTimeoutException e) {
                last = new AnswerException(AnswerException.Kind.TIMEOUT, "Connection to Gemini timed out.", e);
                backoff = pause(attempt, backoff);
                continue;
            } catch (HttpTimeoutException e) {
                last = new AnswerException(AnswerException.Kind.TIMEOUT,
                        "Gemini did not start responding within " + HEADERS_TIMEOUT.toSeconds() + " s.", e);
                backoff = pause(attempt, backoff);
                continue;
            } catch (IOException e) {
                last = new AnswerException(AnswerException.Kind.NETWORK, "Network error: " + describe(e), e);
                backoff = pause(attempt, backoff);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnswerException(AnswerException.Kind.ABORTED, "Interrupted.", e);
            }
            int status = response.statusCode();
            if (status == 200) {
                return consume(response.body(), sink, started, active);
            }
            last = httpError(status, readBounded(response.body()), active);
            if (!RETRYABLE.contains(status)) {
                throw last;
            }
            LOG.log(System.Logger.Level.DEBUG, "Gemini HTTP {0}, attempt {1}/{2}", status, attempt, MAX_ATTEMPTS);
            backoff = pause(attempt, backoff);
        }
        throw Objects.requireNonNull(last);
    }

    // ------------------------------------------------------------------ request

    static String body(AnswerRequest request, ModelProfile profile) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("system_instruction", Map.of("parts", List.of(Map.of("text", request.systemInstruction()))));
        List<Object> contents = new ArrayList<>();
        for (Turn turn : request.history()) {
            contents.add(message("user", turn.user()));
            contents.add(message("model", turn.model()));
        }
        contents.add(message("user", request.userMessage()));
        root.put("contents", contents);
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", profile.temperature());
        generation.put("max_output_tokens", profile.maxOutputTokens());
        generation.put("candidate_count", 1);
        root.put("generation_config", generation);
        return Json.write(root);
    }

    private static Map<String, Object> message(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("parts", List.of(Map.of("text", text)));
        return m;
    }

    // ------------------------------------------------------------------ response

    private static final class State {
        String finishReason;
        int promptTokens;
        int outputTokens;
        int deltas;
        long firstMillis = -1;
    }

    private AnswerStats consume(InputStream body, TokenSink sink, long started, ModelProfile active)
            throws AnswerException {
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicBoolean idle = new AtomicBoolean(false);
        long idleNanos = idleTimeout.toNanos();
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastActivity.get() > idleNanos && idle.compareAndSet(false, true)) {
                closeQuietly(body);
            }
        }, 1, 1, TimeUnit.SECONDS);

        State state = new State();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.nanoTime());
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        event(data.toString(), sink, state, started, body);
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    String value = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                // comment lines (":") and other SSE fields are ignored
            }
            if (!data.isEmpty()) {
                event(data.toString(), sink, state, started, body);
            }
        } catch (IOException e) {
            if (idle.get()) {
                throw new AnswerException(AnswerException.Kind.TIMEOUT,
                        "Gemini stream stalled for " + idleTimeout.toSeconds() + " s.", e);
            }
            throw new AnswerException(AnswerException.Kind.NETWORK, "Stream interrupted: " + describe(e), e);
        } finally {
            watchdog.cancel(false);
        }

        if (state.deltas == 0) {
            if (state.finishReason != null && BLOCKING_REASONS.contains(state.finishReason)) {
                throw new AnswerException(AnswerException.Kind.BLOCKED, "Answer blocked (" + state.finishReason + ").");
            }
            throw new AnswerException(AnswerException.Kind.PROTOCOL, "Gemini returned no text"
                    + (state.finishReason == null ? "." : " (finishReason=" + state.finishReason + ")."));
        }
        return new AnswerStats(active.id(), state.finishReason == null ? "STOP" : state.finishReason,
                state.promptTokens, state.outputTokens, state.firstMillis,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static void event(String json, TokenSink sink, State state, long started, InputStream body)
            throws AnswerException {
        Object chunk;
        try {
            chunk = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new AnswerException(AnswerException.Kind.PROTOCOL, "Unparseable stream chunk: " + e.getMessage(), e);
        }
        if (Json.at(chunk, "error") != null) {
            throw new AnswerException(AnswerException.Kind.UNAVAILABLE,
                    "Gemini reported an error mid-stream: " + Objects.requireNonNullElse(
                            Json.string(chunk, "error", "message"), "unknown"));
        }
        String blockReason = Json.string(chunk, "promptFeedback", "blockReason");
        if (blockReason != null && !blockReason.isEmpty()) {
            throw new AnswerException(AnswerException.Kind.BLOCKED, "Prompt blocked (" + blockReason + ").");
        }
        state.promptTokens = (int) Json.number(chunk, state.promptTokens, "usageMetadata", "promptTokenCount");
        state.outputTokens = (int) Json.number(chunk, state.outputTokens, "usageMetadata", "candidatesTokenCount");
        String finish = Json.string(chunk, "candidates", 0, "finishReason");
        if (finish != null && !finish.isEmpty()) {
            state.finishReason = finish;
        }
        for (Object part : Json.list(chunk, "candidates", 0, "content", "parts")) {
            if (Boolean.TRUE.equals(Json.at(part, "thought"))) {
                continue;
            }
            String text = Json.string(part, "text");
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (state.firstMillis < 0) {
                state.firstMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
            state.deltas++;
            try {
                sink.accept(text);
            } catch (RuntimeException e) {
                closeQuietly(body);
                throw new AnswerException(AnswerException.Kind.ABORTED, "Output consumer aborted: " + e.getMessage(), e);
            }
        }
    }

    private static AnswerException httpError(int status, String body, ModelProfile active) {
        String detail = body;
        try {
            String message = Json.string(Json.parse(body), "error", "message");
            if (message != null) {
                detail = message;
            }
        } catch (Json.JsonException ignored) {
            // keep raw text
        }
        if (detail.length() > 400) {
            detail = detail.substring(0, 400) + "…";
        }
        return switch (status) {
            case 400 -> new AnswerException(AnswerException.Kind.BAD_REQUEST, "Request rejected: " + detail);
            case 401, 403 -> new AnswerException(AnswerException.Kind.AUTHENTICATION,
                    "Authentication failed; check GEMINI_API_KEY.");
            case 404 -> new AnswerException(AnswerException.Kind.BAD_REQUEST,
                    "Model '" + active.id() + "' not found for this key: " + detail);
            case 429 -> new AnswerException(AnswerException.Kind.RATE_LIMITED, "Quota or rate limit reached: " + detail);
            default -> new AnswerException(AnswerException.Kind.UNAVAILABLE, "Gemini unavailable (HTTP " + status + ").");
        };
    }

    private static String readBounded(InputStream in) {
        try (in) {
            return new String(in.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static long pause(int attempt, long backoff) throws AnswerException {
        if (attempt >= MAX_ATTEMPTS) {
            return backoff;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnswerException(AnswerException.Kind.ABORTED, "Interrupted.", e);
        }
        return backoff * 2;
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return h.equalsIgnoreCase("localhost") || h.equals("::1") || h.startsWith("127.");
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // only used to unblock the reader
        }
    }
}
