package org.yazi.prose;

import org.junit.jupiter.api.Test;
import org.yazi.gateway.FinishReason;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ScriptedGateway;
import org.yazi.model.BufferSnapshot;
import org.yazi.model.Span;
import org.yazi.model.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ProsePipelineTest {

    private final ScriptedGateway gateway = new ScriptedGateway();
    private final ProsePipeline pipeline = new ProsePipeline(gateway, ModelCatalog.defaults());

    private ProseOutcome run(BufferSnapshot snapshot, ProseIntent intent, Tier tier) throws GatewayException {
        return pipeline.run(new ProseRequest(snapshot, intent, tier, ""), null, null);
    }

    // --- continue ------------------------------------------------------------------------------

    @Test
    void continuationIsSplicedAtCaretWithSeamSpace() throws Exception {
        gateway.reply("Then ", "he left.");
        BufferSnapshot s = BufferSnapshot.insertion(7, "She ran.", 8);
        List<String> streamed = new ArrayList<>();

        ProseOutcome.Edit edit = (ProseOutcome.Edit) pipeline.run(
                new ProseRequest(s, new ProseIntent.Continue(""), Tier.BALANCED, ""), streamed::add, null);

        assertEquals(List.of("Then ", "he left."), streamed);
        assertEquals(" Then he left.", edit.payload());
        assertEquals(Span.caret(8), edit.replaced());
        assertEquals(7, edit.baseRevision());
    }

    @Test
    void documentTextIsSentExactlyOnce() throws Exception {
        String unique = "The lighthouse keeper counted seventeen gulls.";
        BufferSnapshot s = BufferSnapshot.insertion(1, "Intro line.\n\n" + unique, 13 + unique.length());
        run(s, new ProseIntent.Continue(""), Tier.BALANCED);

        ModelCall call = gateway.lastCall();
        String everything = call.system() + call.user();
        assertEquals(1, occurrences(everything, unique), "document text was duplicated in the prompt");
    }

    @Test
    void promptSizeIsBoundedForHugeDocuments() throws Exception {
        String text = "A plain sentence about nothing in particular. ".repeat(20_000);   // ~920k chars
        run(BufferSnapshot.insertion(1, text, text.length()), new ProseIntent.Continue(""), Tier.BALANCED);

        ModelCall call = gateway.lastCall();
        int promptChars = call.system().length() + call.user().length();
        assertTrue(promptChars < 8_000 + 3_000, "prompt was " + promptChars + " chars");
    }

    @Test
    void generalProfileHasNoFictionMachinery() throws Exception {
        run(BufferSnapshot.insertion(1, "Dear team, the release moved to Friday.", 39),
                new ProseIntent.Continue(""), Tier.BALANCED);
        String system = gateway.lastCall().system().toLowerCase(Locale.ROOT);
        for (String fictionTerm : List.of("camera", "observer", "skeleton", "physical", "interior", "narrat",
                "character", "temple", "priest", "micro-physics")) {
            assertFalse(system.contains(fictionTerm), "general prompt mentions '" + fictionTerm + "'");
        }
    }

    @Test
    void writerStyleReachesTheContract() throws Exception {
        String sample = "\"so sister.how does it feel.\"\n\"i know.who denies that.know,back to your back.\"";
        run(BufferSnapshot.insertion(1, sample, sample.length()), new ProseIntent.Continue(""), Tier.FAST);
        String system = gateway.lastCall().system();
        assertTrue(system.contains("lowercase sentence starts"));
        assertTrue(system.contains("no space after punctuation"));
    }

    @Test
    void explicitLengthBecomesHardLimit() throws Exception {
        run(BufferSnapshot.insertion(1, "Metin.", 6), new ProseIntent.Continue("devam et, 2-3 cümle"), Tier.FAST);
        assertTrue(gateway.lastCall().user().contains("HARD LIMIT: at most 3 cümle."));
    }

    @Test
    void temperaturesStayInTheAgreedRanges() throws Exception {
        String uniform = "One two three four. Five six seven eight. Nine ten eleven twelve.";
        String varied = "Short. This sentence is quite a lot longer than the one before it, by design. Tiny.";
        for (Tier tier : Tier.values()) {
            for (String text : List.of(uniform, varied, "")) {
                run(BufferSnapshot.insertion(1, text, text.length()), new ProseIntent.Continue("go on"), tier);
                double t = gateway.lastCall().temperature();
                assertTrue(t >= 0.35 && t <= 0.45, tier + " continuation temperature " + t);
            }
        }
        BufferSnapshot s = BufferSnapshot.selection(1, "The old dog.", 4, 7);
        run(s, new ProseIntent.Rewrite(s.selection(), ""), Tier.BALANCED);
        assertEquals(0.35, gateway.lastCall().temperature());
        run(s, new ProseIntent.Consult("?", false), Tier.BALANCED);
        assertEquals(0.25, gateway.lastCall().temperature());
    }

    @Test
    void flashModelsRunWithoutThinking() throws Exception {
        run(BufferSnapshot.insertion(1, "Text.", 5), new ProseIntent.Continue(""), Tier.FAST);
        assertEquals(Integer.valueOf(0), gateway.lastCall().thinkingBudget());
        run(BufferSnapshot.insertion(1, "Text.", 5), new ProseIntent.Continue(""), Tier.DEEP);
        assertNull(gateway.lastCall().thinkingBudget());
    }

    @Test
    void sanitisesAndClosesTruncatedOutput() throws Exception {
        gateway.reply("CONTINUATION: \"Run (now").finishWith(FinishReason.MAX_TOKENS);
        ProseOutcome.Edit edit = (ProseOutcome.Edit) run(BufferSnapshot.insertion(1, "He shouted.", 11),
                new ProseIntent.Continue(""), Tier.FAST);
        assertEquals(" \"Run (now)\"", edit.payload());
        assertTrue(edit.truncated());
    }

    @Test
    void emptyDocumentWithoutInstructionIsRejectedBeforeAnyCall() {
        assertThrows(IllegalArgumentException.class,
                () -> run(BufferSnapshot.insertion(1, "  ", 2), new ProseIntent.Continue(""), Tier.FAST));
        assertNull(gateway.lastCall());
    }

    @Test
    void emptyDocumentWithInstructionWrites() throws Exception {
        gateway.reply("Hello team,");
        ProseOutcome.Edit edit = (ProseOutcome.Edit) run(BufferSnapshot.insertion(1, "", 0),
                new ProseIntent.Continue("Write a greeting line"), Tier.FAST);
        assertEquals("Hello team,", edit.payload());
        assertTrue(gateway.lastCall().user().contains("(empty document)"));
    }

    // --- rewrite -------------------------------------------------------------------------------

    @Test
    void rewriteReplacesTargetAndDefaultsToPolish() throws Exception {
        gateway.reply("tired");
        BufferSnapshot s = BufferSnapshot.selection(3, "The old dog slept.", 4, 7);
        ProseOutcome.Edit edit = (ProseOutcome.Edit) run(s, new ProseIntent.Rewrite(s.selection(), ""), Tier.BALANCED);

        assertEquals(new Span(4, 7), edit.replaced());
        assertEquals("tired", edit.payload());
        ModelCall call = gateway.lastCall();
        assertTrue(call.user().contains("[TARGET]\nold\n[/TARGET]"));
        assertTrue(call.user().contains(ProsePrompts.DEFAULT_REWRITE));
        assertTrue(call.system().contains("[SCOPE: replace TARGET only]"));
    }

    // --- consult -------------------------------------------------------------------------------

    @Test
    void consultReturnsAnswerAndNeverEdits() throws Exception {
        gateway.reply("The second sentence repeats the first.");
        ProseOutcome out = run(BufferSnapshot.insertion(1, "One. One again.", 15),
                new ProseIntent.Consult("Is anything repeated?", false), Tier.FAST);

        ProseOutcome.Answer answer = assertInstanceOf(ProseOutcome.Answer.class, out);
        assertEquals("The second sentence repeats the first.", answer.text());
        assertTrue(gateway.lastCall().user().contains("[QUESTION]\nIs anything repeated?"));
    }

    // --- routing -------------------------------------------------------------------------------

    @Test
    void routerUsesGestureNotWording() {
        BufferSnapshot noSelection = BufferSnapshot.insertion(1, "Para one.\n\nPara two.", 20);
        assertInstanceOf(ProseIntent.Continue.class,
                ProseRouter.route(noSelection, "describe what she sees", ProseRouter.Gesture.APPLY));
        assertInstanceOf(ProseIntent.Consult.class,
                ProseRouter.route(noSelection, "tighten this", ProseRouter.Gesture.ASK));
    }

    @Test
    void routerSendsSelectionToRewriteAndOperatorsToPrecedingBlock() {
        BufferSnapshot selected = BufferSnapshot.selection(1, "Para one.\n\nPara two.", 0, 4);
        assertEquals(new Span(0, 4),
                ((ProseIntent.Rewrite) ProseRouter.route(selected, "", ProseRouter.Gesture.APPLY)).target());

        BufferSnapshot caretAtEnd = BufferSnapshot.insertion(1, "Para one.\n\nPara two.", 20);
        ProseIntent op = ProseRouter.route(caretAtEnd, "two -> three", ProseRouter.Gesture.APPLY);
        assertEquals(new Span(11, 20), ((ProseIntent.Rewrite) op).target());
    }

    @Test
    void modelCatalogReadsOverrides() {
        ModelCatalog c = ModelCatalog.from(java.util.Map.of("YAZI_MODEL_DEEP", "gemini-next-pro"));
        assertEquals("gemini-next-pro", c.modelFor(Tier.DEEP));
        assertEquals(ModelCatalog.DEFAULT_FLASH, c.modelFor(Tier.FAST));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
