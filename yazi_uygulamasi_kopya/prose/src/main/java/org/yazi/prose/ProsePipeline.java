package org.yazi.prose;

import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.StreamResult;
import org.yazi.gateway.TokenSink;
import org.yazi.model.BufferSnapshot;
import org.yazi.model.ContextWindow;
import org.yazi.model.Span;
import org.yazi.model.Tier;
import org.yazi.text.ContextWindowPolicy;
import org.yazi.text.OutputSanitizer;
import org.yazi.text.SpliceEngine;
import org.yazi.text.StyleMetrics;
import org.yazi.text.StyleProfile;
import org.yazi.text.SyntaxCloser;

import java.util.Objects;

/**
 * The general-purpose prose pipeline: continue, rewrite, ask.
 *
 * <p>Each call is a pure transformation of the current buffer: fixed context window, one model call, local
 * clean-up, seam-aware splice. Nothing is remembered between calls. Stateless and thread-safe.</p>
 */
public final class ProsePipeline {

    private final ModelGateway gateway;
    private final ModelCatalog models;

    public ProsePipeline(ModelGateway gateway, ModelCatalog models) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.models = Objects.requireNonNull(models, "models");
    }

    /**
     * @param sink         receives raw streamed text as it arrives (for live preview); may be null
     * @param cancellation may be null
     * @throws IllegalArgumentException if there is nothing to work with (empty document and no instruction)
     */
    public ProseOutcome run(ProseRequest request, TokenSink sink, CancellationToken cancellation)
            throws GatewayException {
        TokenSink target = (sink == null) ? TokenSink.DISCARD : sink;
        return switch (request.intent()) {
            case ProseIntent.Continue c -> continueText(request, c, target, cancellation);
            case ProseIntent.Rewrite r -> rewrite(request, r, target, cancellation);
            case ProseIntent.Consult q -> consult(request, q, target, cancellation);
        };
    }

    private ProseOutcome.Edit continueText(ProseRequest request, ProseIntent.Continue intent, TokenSink sink,
                                           CancellationToken cancellation) throws GatewayException {
        BufferSnapshot snapshot = request.snapshot();
        ContextWindow window = ContextWindowPolicy.forContinue(snapshot, request.tier(), request.brief());
        if (window.before().isBlank() && intent.instruction().isEmpty()) {
            throw new IllegalArgumentException("The document is empty: type something or give an instruction.");
        }
        StyleProfile style = StyleMetrics.measure(window.before());
        ProsePrompts.Prompt prompt = ProsePrompts.continuation(window, intent.instruction(), style);

        ModelCall call = call(request.tier(), prompt)
                .withTemperature(Sampling.continueTemperature(request.tier(), style))
                .withMaxOutputTokens(Sampling.continueMaxTokens(request.tier()));
        StreamResult result = gateway.stream(call, sink, cancellation);

        return edit(snapshot, Span.caret(snapshot.caret()), result, window);
    }

    private ProseOutcome.Edit rewrite(ProseRequest request, ProseIntent.Rewrite intent, TokenSink sink,
                                      CancellationToken cancellation) throws GatewayException {
        BufferSnapshot snapshot = request.snapshot();
        Span target = intent.target().clampTo(snapshot.length());
        ContextWindow window = ContextWindowPolicy.forRewrite(snapshot, target, request.tier(), request.brief());
        StyleProfile style = StyleMetrics.measure(window.before() + window.target() + window.after());
        ProsePrompts.Prompt prompt = ProsePrompts.rewrite(window, intent.instruction(), style);

        ModelCall call = call(request.tier(), prompt)
                .withTemperature(Sampling.REWRITE_TEMPERATURE)
                .withMaxOutputTokens(Sampling.rewriteMaxTokens(request.tier(), target.length()));
        StreamResult result = gateway.stream(call, sink, cancellation);

        return edit(snapshot, target, result, window);
    }

    private ProseOutcome.Answer consult(ProseRequest request, ProseIntent.Consult intent, TokenSink sink,
                                        CancellationToken cancellation) throws GatewayException {
        ContextWindow window = ContextWindowPolicy.forConsult(
                request.snapshot(), request.tier(), request.brief(), intent.wholeDocument());
        ProsePrompts.Prompt prompt = ProsePrompts.consult(window, intent.instruction());

        ModelCall call = call(request.tier(), prompt)
                .withTemperature(Sampling.CONSULT_TEMPERATURE)
                .withMaxOutputTokens(Sampling.consultMaxTokens(request.tier()));
        StreamResult result = gateway.stream(call, sink, cancellation);

        String text = OutputSanitizer.clean(result.text()).strip();
        return new ProseOutcome.Answer(text, result.truncated(), result.usage(), window.totalChars());
    }

    private ModelCall call(Tier tier, ProsePrompts.Prompt prompt) {
        String model = models.modelFor(tier);
        return ModelCall.of(model, prompt.system(), prompt.user())
                .withThinkingBudget(models.thinkingBudgetFor(model));
    }

    private static ProseOutcome.Edit edit(BufferSnapshot snapshot, Span target, StreamResult result,
                                          ContextWindow window) {
        String cleaned = OutputSanitizer.clean(result.text());
        if (result.truncated()) {
            cleaned = SyntaxCloser.closeHanging(cleaned);
        }
        SpliceEngine.SpliceResult spliced = SpliceEngine.splice(snapshot.text(), target, cleaned);
        String payload = spliced.text().substring(spliced.inserted().start(), spliced.inserted().end());
        return new ProseOutcome.Edit(snapshot.revision(), target, payload, result.truncated(), result.usage(),
                window.totalChars());
    }
}
