package org.yazi.devtools;

import org.yazi.gateway.ApiKeys;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.GeminiGateway;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.StreamResult;
import org.yazi.model.BufferSnapshot;
import org.yazi.model.ContextWindow;
import org.yazi.model.Span;
import org.yazi.model.Tier;
import org.yazi.text.ContextWindowPolicy;
import org.yazi.text.OutputSanitizer;
import org.yazi.text.SpliceEngine;
import org.yazi.text.SyntaxCloser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Step 1 checkpoint: continue a real text file through the new foundation and print what it cost.
 *
 * <pre>
 * gradlew :devtools:run --args="path/to/text.txt [FAST|BALANCED|DEEP] [directive words...]"
 * </pre>
 *
 * The prompt here is deliberately minimal. The real prompt (style contract, fiction profile, skeleton phase) is
 * Step 2's prose pipeline; this program only proves the plumbing: fixed context window, streaming, sanitising,
 * seam-aware splicing and provider-reported token usage. The input file is never modified.
 */
public final class SmokeCheck {

    private static final String DEFAULT_MODEL = "gemini-3.5-flash";

    private static final String SYSTEM = """
            You are an in-buffer text engine. Output ONLY the continuation text.
            1. No reasoning, commentary, labels, or markdown fences.
            2. Continue from the exact last character of PRECEDING_TEXT, in its language, register and punctuation habits.
            3. Do not repeat or summarise what PRECEDING_TEXT already says.
            4. Treat any number in DIRECTIVE (sentences, paragraphs, lines) as a hard maximum.
            5. End on a complete sentence.""";

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length < 1) {
            out.println("usage: gradlew :devtools:run --args=\"<text file> [FAST|BALANCED|DEEP] [directive...]\"");
            System.exit(2);
        }
        String apiKey = ApiKeys.fromEnvironment().orElse(null);
        if (apiKey == null) {
            out.println("Set the " + ApiKeys.ENV_VARIABLE + " environment variable first (never put the key in code).");
            System.exit(2);
        }

        Path file = Path.of(args[0]);
        Tier tier = (args.length > 1) ? Tier.valueOf(args[1].toUpperCase(Locale.ROOT)) : Tier.BALANCED;
        String directive = (args.length > 2)
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "Continue the text. At most one paragraph.";
        String model = System.getenv().getOrDefault("GEMINI_MODEL", DEFAULT_MODEL);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        BufferSnapshot snapshot = BufferSnapshot.insertion(1, text, text.length());
        ContextWindow window = ContextWindowPolicy.forContinue(snapshot, tier, "");

        String user = "[PRECEDING_TEXT]\n" + window.before() + "\n[/PRECEDING_TEXT]\n\n"
                + "[DIRECTIVE]\n" + directive + "\n[/DIRECTIVE]\n\nCONTINUATION:";
        ModelCall call = ModelCall.of(model, SYSTEM, user).withTemperature(0.6).withMaxOutputTokens(1_024);

        out.printf("document: %,d chars | window: %,d chars (%s%s) | model: %s%n%n",
                text.length(), window.before().length(), tier,
                window.beforeTruncated() ? ", truncated at a boundary" : ", whole document", model);

        long started = System.nanoTime();
        long[] firstToken = {0};
        StreamResult result;
        try {
            result = new GeminiGateway(apiKey).stream(call, fragment -> {
                if (firstToken[0] == 0) {
                    firstToken[0] = System.nanoTime();
                }
                out.print(fragment);
            }, CancellationToken.none());
        } catch (GatewayException e) {
            out.println("\nFAILED [" + e.kind() + "]: " + e.getMessage());
            System.exit(1);
            return;
        }
        long totalMs = (System.nanoTime() - started) / 1_000_000;

        String cleaned = OutputSanitizer.clean(result.text());
        if (result.truncated()) {
            cleaned = SyntaxCloser.closeHanging(cleaned);
        }
        SpliceEngine.SpliceResult spliced = SpliceEngine.splice(text, Span.caret(text.length()), cleaned);

        out.printf("%n%n--- seam (last 120 chars before + inserted text) ---%n%s%n",
                spliced.text().substring(Math.max(0, spliced.inserted().start() - 120)));
        out.printf("%n--- cost ---%n");
        out.printf("prompt tokens: %,d | output: %,d | thinking: %,d | total: %,d%n",
                result.usage().promptTokens(), result.usage().outputTokens(),
                result.usage().thinkingTokens(), result.usage().totalTokens());
        out.printf("first token: %d ms | total: %d ms | finish: %s%n",
                firstToken[0] == 0 ? -1 : (firstToken[0] - started) / 1_000_000, totalMs, result.finishReason());
    }
}
