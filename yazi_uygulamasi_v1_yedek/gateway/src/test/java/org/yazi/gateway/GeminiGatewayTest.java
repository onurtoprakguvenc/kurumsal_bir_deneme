package org.yazi.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.GatewayException.Kind;
import org.yazi.gateway.schema.SchemaOptions;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GeminiGatewayTest {

    private FakeGemini fake;
    private GeminiGateway gateway;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeGemini();
        gateway = fake.gateway();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private static ModelCall call() {
        return ModelCall.of("gemini-test", "SYSTEM", "USER TEXT");
    }

    // --- streaming -------------------------------------------------------------------------------

    @Test
    void streamsFragmentsInOrderAndReportsUsage() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex,
                FakeGemini.textEvent("She "),
                FakeGemini.textEvent("ran "),
                FakeGemini.finishEvent("home.", "STOP", 812, 9)));

        List<String> seen = new ArrayList<>();
        StreamResult result = gateway.stream(call(), seen::add, null);

        assertEquals(List.of("She ", "ran ", "home."), seen);
        assertEquals("She ran home.", result.text());
        assertEquals(FinishReason.STOP, result.finishReason());
        assertEquals(812, result.usage().promptTokens());
        assertEquals(9, result.usage().outputTokens());
    }

    @Test
    void thinkingPartsNeverReachTheSink() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"planning the beat\",\"thought\":true},{\"text\":\"She ran.\"}]},\"finishReason\":\"STOP\"}]}"));
        assertEquals("She ran.", gateway.stream(call(), TokenSink.DISCARD, null).text());
    }

    @Test
    void maxTokensIsReportedNotThrown() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("She ran and", "MAX_TOKENS", 10, 5)));
        StreamResult result = gateway.stream(call(), TokenSink.DISCARD, null);
        assertTrue(result.truncated());
    }

    @Test
    void safetyStopThrowsBlocked() {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("", "SAFETY", 10, 0)));
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, null));
        assertEquals(Kind.BLOCKED, e.kind());
    }

    @Test
    void promptBlockThrowsBlocked() {
        fake.respondWith(ex -> FakeGemini.sse(ex, "{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}"));
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, null));
        assertEquals(Kind.BLOCKED, e.kind());
    }

    @Test
    void emptyStreamIsMalformed() {
        fake.respondWith(ex -> FakeGemini.sse(ex));
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, null));
        assertEquals(Kind.MALFORMED, e.kind());
    }

    // --- request shape ---------------------------------------------------------------------------

    @Test
    void keyTravelsInHeaderNeverInUrl() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("ok", "STOP", 1, 1)));
        gateway.stream(call(), TokenSink.DISCARD, null);

        FakeGemini.Captured req = fake.last();
        assertEquals("test-key", req.apiKeyHeader());
        assertEquals("/v1beta/models/gemini-test:streamGenerateContent", req.path());
        assertEquals("alt=sse", req.query());
        assertFalse(req.body().contains("test-key"));
    }

    @Test
    void payloadHasExactlyOneUserTurn() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("ok", "STOP", 1, 1)));
        ModelCall c = call().withTemperature(0.3).withMaxOutputTokens(700).withThinkingBudget(0)
                .withImages(List.of(new InlineImage("image/png", new byte[]{1, 2, 3})));
        gateway.stream(c, TokenSink.DISCARD, null);

        JsonNode body = new ObjectMapper().readTree(fake.last().body());
        assertEquals("SYSTEM", body.at("/systemInstruction/parts/0/text").asText());
        assertEquals(1, body.get("contents").size());
        assertEquals("user", body.at("/contents/0/role").asText());
        assertEquals("image/png", body.at("/contents/0/parts/0/inlineData/mimeType").asText());
        assertEquals("AQID", body.at("/contents/0/parts/0/inlineData/data").asText());
        assertEquals("USER TEXT", body.at("/contents/0/parts/1/text").asText());
        assertEquals(0.3, body.at("/generationConfig/temperature").asDouble());
        assertEquals(700, body.at("/generationConfig/maxOutputTokens").asInt());
        assertEquals(0, body.at("/generationConfig/thinkingConfig/thinkingBudget").asInt());
        assertTrue(body.at("/generationConfig/responseSchema").isMissingNode());
    }

    @Test
    void thinkingConfigOmittedByDefault() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("ok", "STOP", 1, 1)));
        gateway.stream(call(), TokenSink.DISCARD, null);
        JsonNode body = new ObjectMapper().readTree(fake.last().body());
        assertTrue(body.at("/generationConfig/thinkingConfig").isMissingNode());
    }

    // --- HTTP errors -----------------------------------------------------------------------------

    @Test
    void rateLimitCarriesRetryAfter() {
        fake.respondWith(ex -> FakeGemini.status(ex, 429, "{\"error\":{\"message\":\"Quota exceeded\"}}", "17"));
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, null));
        assertEquals(Kind.RATE_LIMITED, e.kind());
        assertEquals(Duration.ofSeconds(17), e.retryAfter().orElseThrow());
        assertTrue(e.getMessage().contains("Quota exceeded"));
        assertTrue(e.isRetryable());
    }

    @Test
    void rejectedKeyIsAuthAndDoesNotLeakKey() {
        fake.respondWith(ex -> FakeGemini.status(ex, 403, "{\"error\":{\"message\":\"API key not valid\"}}", null));
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, null));
        assertEquals(Kind.AUTH, e.kind());
        assertFalse(e.getMessage().contains("test-key"));
        assertFalse(e.isRetryable());
    }

    @Test
    void serverErrorIsRetryableHttp() {
        fake.respondWith(ex -> FakeGemini.status(ex, 503, "overloaded", null));
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, null));
        assertEquals(Kind.HTTP, e.kind());
        assertEquals(503, e.httpStatus());
        assertTrue(e.isRetryable());
    }

    // --- cancellation ----------------------------------------------------------------------------

    @Test
    void cancellingMidStreamStopsPromptly() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        fake.respondWith(ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write(("data: " + FakeGemini.textEvent("first ") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            release.await(10, TimeUnit.SECONDS);   // the server stalls; only cancellation can end the read
        });

        CancellationToken token = new CancellationToken();
        long started = System.nanoTime();
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.stream(call(), fragment -> token.cancel(), token));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();

        assertEquals(Kind.CANCELLED, e.kind());
        assertTrue(elapsedMs < 3_000, "cancellation took " + elapsedMs + " ms");
        assertFalse(Thread.currentThread().isInterrupted(), "interrupt flag must not leak to the caller");
    }

    @Test
    void cancelledBeforeStartNeverCallsServer() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        GatewayException e = assertThrows(GatewayException.class, () -> gateway.stream(call(), TokenSink.DISCARD, token));
        assertEquals(Kind.CANCELLED, e.kind());
        assertNull(fake.last());
    }

    // --- structured output -----------------------------------------------------------------------

    record Digest(String targetObjective, List<String> failureModes) {}

    @Test
    void structuredOutputIsParsedAndSchemaIsSent() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex,
                FakeGemini.textEvent("{\"targetObjective\":\"Summarise\","),
                FakeGemini.finishEvent("\"failureModes\":[\"empty input\"]}", "STOP", 40, 12)));

        Structured<Digest> result = gateway.structured(call(), Digest.class, SchemaOptions.none(), null);

        assertEquals(new Digest("Summarise", List.of("empty input")), result.value());
        assertEquals(40, result.usage().promptTokens());
        JsonNode body = new ObjectMapper().readTree(fake.last().body());
        assertEquals("application/json", body.at("/generationConfig/responseMimeType").asText());
        assertEquals("OBJECT", body.at("/generationConfig/responseSchema/type").asText());
    }

    @Test
    void truncatedStructuredOutputThrows() {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("{\"targetObjective\":\"Sum", "MAX_TOKENS", 40, 2048)));
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.structured(call(), Digest.class, null, null));
        assertEquals(Kind.TRUNCATED, e.kind());
    }

    @Test
    void mismatchedJsonIsMalformed() {
        fake.respondWith(ex -> FakeGemini.sse(ex, FakeGemini.finishEvent("{\"failureModes\":\"not a list\"}", "STOP", 1, 1)));
        GatewayException e = assertThrows(GatewayException.class,
                () -> gateway.structured(call(), Digest.class, null, null));
        assertEquals(Kind.MALFORMED, e.kind());
    }

    @Test
    void rawControlCharactersInsideJsonStringsAreTolerated() throws Exception {
        // The model's JSON text contains a literal tab inside a string value (escaped once for the SSE envelope).
        fake.respondWith(ex -> FakeGemini.sse(ex,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"targetObjective\\\":\\\"a\tb\\\",\\\"failureModes\\\":[]}\"}]},\"finishReason\":\"STOP\"}]}"));
        assertEquals("a\tb", gateway.structured(call(), Digest.class, null, null).value().targetObjective());
    }

    @Test
    void fencedJsonIsStillParsed() throws Exception {
        fake.respondWith(ex -> FakeGemini.sse(ex,
                FakeGemini.finishEvent("```json\n{\"targetObjective\":\"x\",\"failureModes\":[]}\n```", "STOP", 1, 1)));
        assertEquals("x", gateway.structured(call(), Digest.class, null, null).value().targetObjective());
    }
}
