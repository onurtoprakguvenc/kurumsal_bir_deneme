package org.yazi.desktop;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.GatewayException.Kind;
import org.yazi.gateway.ScriptedGateway;
import org.yazi.gateway.Usage;
import org.yazi.model.Span;
import org.yazi.prose.ModelCatalog;
import org.yazi.prose.ProseIntent;
import org.yazi.prose.ProsePipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.yazi.desktop.OperationController.Activity.ERROR;
import static org.yazi.desktop.OperationController.Activity.IDLE;
import static org.yazi.desktop.OperationController.Activity.REVIEW;
import static org.yazi.desktop.OperationController.Activity.WORKING;

/** What the controller tells the window: activity for the status dot, paced streaming, readable errors. */
class OperationFeedbackTest {

    private ScriptedGateway gateway;
    private EditorPane editor;
    private OperationController controller;
    private final List<OperationController.Activity> activities = Collections.synchronizedList(new ArrayList<>());
    private final List<String> statuses = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    @BeforeEach
    void setUp() {
        gateway = new ScriptedGateway();
        OperationController.View view = new OperationController.View() {
            @Override
            public void answerStarted(String question) {}

            @Override
            public void answerAppend(String fragment) {}

            @Override
            public void answerFinished(String text, boolean truncated) {}

            @Override
            public void status(String message) {
                statuses.add(message);
            }

            @Override
            public void cost(Usage usage, int windowChars) {}

            @Override
            public void activity(OperationController.Activity activity) {
                activities.add(activity);
            }
        };
        Fx.run(() -> {
            editor = new EditorPane();
            controller = new OperationController(editor, new ProsePipeline(gateway, ModelCatalog.defaults()), view,
                    () -> "");
        });
    }

    private void awaitIdle() {
        Fx.await("idle", () -> controller.state() == OperationController.State.IDLE);
    }

    @Test
    void continuationReportsWorkingThenIdle() {
        gateway.reply("More.");
        Fx.run(() -> {
            editor.load("Text.");
            editor.area().moveTo(5);
            controller.start(new ProseIntent.Continue(""));
        });
        awaitIdle();
        assertEquals(List.of(WORKING, IDLE), activities);
    }

    @Test
    void rewriteReportsReviewAndAcceptReturnsToIdle() {
        gateway.reply("tired");
        Fx.run(() -> {
            editor.load("The old dog.");
            controller.start(new ProseIntent.Rewrite(new Span(4, 7), ""));
        });
        Fx.await("review", () -> controller.state() == OperationController.State.REVIEW);
        Fx.run(controller::accept);
        assertEquals(List.of(WORKING, REVIEW, IDLE), activities);
    }

    @Test
    void failureReportsErrorWithAReadableMessage() {
        gateway.failWith(new GatewayException(Kind.HTTP, "HTTP 404: models/x is not found", 404, null, null));
        Fx.run(() -> {
            editor.load("Text.");
            editor.area().moveTo(5);
            controller.start(new ProseIntent.Continue(""));
        });
        awaitIdle();
        assertEquals(List.of(WORKING, ERROR), activities);
        assertTrue(statuses.getLast().contains("YAZI_MODEL_"), statuses.getLast());
    }

    @Test
    void cancelReportsIdle() {
        CountDownLatch release = new CountDownLatch(1);
        gateway.reply("a", "b").stallAfterFirstFragment(release);
        Fx.run(() -> {
            editor.load("x");
            editor.area().moveTo(1);
            controller.start(new ProseIntent.Continue(""));
        });
        Fx.await("running", () -> controller.state() == OperationController.State.RUNNING);
        Fx.run(controller::cancelOrReject);
        release.countDown();
        assertEquals(List.of(WORKING, IDLE), activities);
    }

    @Test
    void aLongReplyStillLandsCompletelyAndIntact() {
        String reply = " Bir zamanlar 🌙 uzak bir ülkede, ".repeat(40);
        gateway.reply(reply.substring(0, 200), reply.substring(200));
        Fx.run(() -> {
            editor.load("Başlangıç.");
            editor.area().moveTo(10);
            controller.start(new ProseIntent.Continue(""));
        });
        awaitIdle();
        String text = Fx.call(() -> editor.area().getText());
        assertTrue(text.startsWith("Başlangıç."));
        assertTrue(text.contains("🌙"), "pacing must not corrupt surrogate pairs");
        assertEquals(40L, text.codePoints().filter(c -> c == 0x1F319).count());
    }

    // --- error wording ---------------------------------------------------------------------------

    @Test
    void everyErrorKindHasAnActionableMessage() {
        for (Kind kind : Kind.values()) {
            String m = OperationController.describe(new GatewayException(kind, "raw detail", 0, null, null));
            assertNotNull(m, kind.name());
            assertFalse(m.isBlank(), kind.name());
        }
        assertEquals("Rate limit reached; try again in 3 h.", OperationController.describe(
                new GatewayException(Kind.RATE_LIMITED, "q", 429, Duration.ofHours(3), null)));
        assertTrue(OperationController.describe(new GatewayException(Kind.HTTP, "x", 503, null, null))
                .contains("HTTP 503"));
        assertEquals("HTTP 400: bad", OperationController.describe(
                new GatewayException(Kind.HTTP, "HTTP 400: bad", 400, null, null)), "other HTTP errors keep the detail");
        assertTrue(OperationController.describe(new GatewayException(Kind.NETWORK, "No response within 60s."))
                .startsWith("Network problem: No response within 60s."));
    }

    @Test
    void humanDurations() {
        assertEquals("45 s", OperationController.humanDuration(45));
        assertEquals("3 min", OperationController.humanDuration(180));
        assertEquals("2 h", OperationController.humanDuration(7_200));
    }
}
