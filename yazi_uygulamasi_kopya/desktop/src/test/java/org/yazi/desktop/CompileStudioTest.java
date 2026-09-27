package org.yazi.desktop;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.Usage;
import org.yazi.metaprompt.MetaPromptContracts.CompiledPromptSpec;
import org.yazi.metaprompt.MetaPromptContracts.PromptIntentDigest;
import org.yazi.metaprompt.MetaPromptPipeline;
import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.VisualStylePreset;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;
import org.yazi.visual.GeminiModel;
import org.yazi.visual.IngestionMode;
import org.yazi.visual.PromptCompiler;
import org.yazi.visual.VisualRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Compile studio dialogs, filled in and confirmed with Ctrl+Enter without being shown. What matters most:
 * they still produce exactly the engine inputs the old dialogs did.
 */
class CompileStudioTest {

    private CompileActions compile;
    private MetaPromptFlow meta;

    @BeforeAll
    static void toolkit() {
        Fx.start();
    }

    static final OperationController.View SILENT = new OperationController.View() {
        @Override
        public void answerStarted(String question) {}

        @Override
        public void answerAppend(String fragment) {}

        @Override
        public void answerFinished(String text, boolean truncated) {}

        @Override
        public void status(String message) {}

        @Override
        public void cost(Usage usage, int windowChars) {}
    };

    @BeforeEach
    void setUp() {
        Fx.run(() -> {
            KeyedGateway gateway = new KeyedGateway(null);
            compile = new CompileActions(null, new EditorPane(), gateway, SILENT, () -> false,
                    () -> UiSettings.Theme.LIGHT);
            meta = new MetaPromptFlow(null, new MetaPromptPipeline(gateway, null), SILENT, () -> UiSettings.Theme.DARK);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T byId(Dialog<?> d, String id) {
        Node n = d.getDialogPane().lookup("#" + id);
        assertNotNull(n, "no #" + id);
        return (T) n;
    }

    private static void confirm(Dialog<?> d) {
        UiComponentsTest.press(d.getDialogPane(), KeyCode.ENTER, false, true);
    }

    private static void pick(Dialog<?> d, String segmentedId, String label) {
        HBox seg = byId(d, segmentedId);
        for (Node n : seg.getChildren()) {
            if (((ToggleButton) n).getText().equals(label)) {
                ((ToggleButton) n).fire();
                return;
            }
        }
        fail("no segment " + label + " in #" + segmentedId);
    }

    // --- video -----------------------------------------------------------------------------------

    @Test
    void videoDefaultsProduceTheSameInputAsBefore() {
        Fx.run(() -> {
            Dialog<RawUserPromptInput> d = compile.videoDialog("Rain on a neon street at night.");
            confirm(d);
            RawUserPromptInput in = d.getResult();
            assertNotNull(in);
            assertEquals("Rain on a neon street at night.", in.userDescription());
            assertEquals(10.0, in.targetDurationSec());
            assertEquals(AspectRatio.RATIO_16_9, in.aspectRatio());
            assertNull(in.stylePreferences().preset(), "preset inferred by default");
            assertEquals(ImperfectionLevel.OFF, in.imperfectionLevel());
            assertNull(in.suppressDetailAreas());
        });
    }

    @Test
    void videoChoicesReachTheEngineInputAndItCompilesLocally() {
        Fx.run(() -> {
            Dialog<RawUserPromptInput> d = compile.videoDialog("A woman walks along a rainy street at night.");
            ((Slider) byId(d, "video-duration")).setValue(23.4);
            pick(d, "video-aspect", "9:16");
            ComboBox<VisualStylePreset> preset = byId(d, "video-preset");
            preset.setValue(VisualStylePreset.NEO_NOIR);
            pick(d, "video-imperfection", "Light");
            ((TextField) byId(d, "video-suppress")).setText("sky");
            confirm(d);

            RawUserPromptInput in = d.getResult();
            assertEquals(23.0, in.targetDurationSec());
            assertEquals(AspectRatio.RATIO_9_16, in.aspectRatio());
            assertEquals(VisualStylePreset.NEO_NOIR, in.stylePreferences().preset());
            assertEquals(ImperfectionLevel.LIGHT, in.imperfectionLevel());
            assertEquals("sky", in.suppressDetailAreas());
            assertInstanceOf(PipelineResult.Success.class, new PromptPipelineOrchestrator().run(in));
        });
    }

    @Test
    void videoCannotCompileAnEmptyScene() {
        Fx.run(() -> {
            Dialog<RawUserPromptInput> d = compile.videoDialog("   ");
            assertTrue(UiComponentsTest.run(d).isDisabled());
            confirm(d);
            assertNull(d.getResult());
            ((TextArea) byId(d, "video-scene")).setText("Waves at dawn.");
            assertFalse(UiComponentsTest.run(d).isDisabled());
        });
    }

    @Test
    void presetLabelsReadLikeWords() {
        assertEquals("Inferred from the scene", CompileActions.presetLabel(null));
        assertEquals("Cinematic 35 mm", CompileActions.presetLabel(VisualStylePreset.CINEMATIC_35MM));
        assertEquals("Hyperrealistic 8K", CompileActions.presetLabel(VisualStylePreset.HYPERREALISTIC_8K));
        assertEquals("MVT Cyberpunk", CompileActions.presetLabel(VisualStylePreset.MVT_CYBERPUNK));
        assertEquals("Neo-noir", CompileActions.presetLabel(VisualStylePreset.NEO_NOIR));
        assertEquals("Documentary", CompileActions.presetLabel(VisualStylePreset.DOCUMENTARY));
        for (VisualStylePreset p : VisualStylePreset.values()) {
            assertFalse(CompileActions.presetLabel(p).contains("_"), p.name());
        }
        assertEquals("Three Stage", CompileActions.titleCase("THREE_STAGE"));
        assertEquals("", CompileActions.titleCase("_"));
    }

    // --- image -----------------------------------------------------------------------------------

    @Test
    void imageDefaultsProduceTheSameRequestAsBefore() {
        Fx.run(() -> {
            Dialog<VisualRequest> d = compile.imageDialog("A lighthouse in a storm.");
            confirm(d);
            VisualRequest r = d.getResult();
            assertEquals(IngestionMode.DIRECT, r.mode());
            assertEquals("A lighthouse in a storm.", r.rawScene());
            assertEquals("A lighthouse in a storm.", r.slotTwo(), "slot 2 is prefilled as before");
            assertEquals(PromptCompiler.EngineProfile.MIDJOURNEY_V6, r.engine());
            assertEquals(GeminiModel.FLASH, r.model());
            assertEquals(VisualRequest.DEFAULT_ASPECT_RATIO, r.aspectRatio());
            assertEquals(VisualRequest.DEFAULT_FLAGS, r.flags());
            assertTrue(r.regionalPasses());
        });
    }

    @Test
    void threeStageModeSwapsTheSceneForSlotsAndRequiresTheAction() {
        Fx.run(() -> {
            Dialog<VisualRequest> d = compile.imageDialog("");
            TextArea scene = byId(d, "image-scene");
            TextArea slot2 = byId(d, "image-slot2");
            assertTrue(scene.getParent().isVisible());
            assertFalse(slot2.getParent().isVisible());

            pick(d, "image-mode", "Three-stage");
            assertFalse(scene.getParent().isVisible());
            assertTrue(slot2.getParent().isVisible());
            assertTrue(UiComponentsTest.run(d).isDisabled());
            assertTrue(((Label) byId(d, "studio-validation")).getText().contains("slot 2"));

            ((TextArea) byId(d, "image-slot1")).setText("Harbour, 1920s, dusk");
            slot2.setText("A sailor throws a rope");
            ((TextArea) byId(d, "image-slot3")).setText("35mm, low angle");
            confirm(d);
            VisualRequest r = d.getResult();
            assertEquals(IngestionMode.THREE_STAGE, r.mode());
            assertEquals("Harbour, 1920s, dusk", r.slotOne());
            assertEquals("A sailor throws a rope", r.slotTwo());
            assertEquals("35mm, low angle", r.slotThree());
        });
    }

    @Test
    void midjourneyFlagsAreDisabledForFluxAndSettingsReachTheRequest() {
        Fx.run(() -> {
            Dialog<VisualRequest> d = compile.imageDialog("Scene");
            TextField flags = byId(d, "image-flags");
            assertFalse(flags.isDisabled());
            pick(d, "image-engine", PromptCompiler.EngineProfile.FLUX_1_DEV.getDisplayName());
            assertTrue(flags.isDisabled());

            pick(d, "image-mode", "Narrative");
            ComboBox<GeminiModel> model = byId(d, "image-model");
            model.setValue(GeminiModel.PRO);
            ((TextField) byId(d, "image-aspect")).setText("3:2");
            ((CheckBox) byId(d, "image-regional")).setSelected(false);
            confirm(d);
            VisualRequest r = d.getResult();
            assertEquals(IngestionMode.NARRATIVE, r.mode());
            assertEquals(PromptCompiler.EngineProfile.FLUX_1_DEV, r.engine());
            assertEquals(GeminiModel.PRO, r.model());
            assertEquals("3:2", r.aspectRatio());
            assertFalse(r.regionalPasses());
        });
    }

    @Test
    void everyIngestionModeIsExplained() {
        for (IngestionMode m : IngestionMode.values()) {
            assertFalse(CompileActions.modeHint(m).isBlank());
        }
    }

    // --- meta-prompt -----------------------------------------------------------------------------

    @Test
    void stage1NeedsAConcept() {
        Fx.run(() -> {
            Dialog<String> d = meta.stage1Dialog("");
            assertTrue(UiComponentsTest.run(d).isDisabled());
            ((TextArea) byId(d, "meta-concept")).setText("Summarise support tickets");
            confirm(d);
            assertEquals("Summarise support tickets", d.getResult());
            assertTrue(d.getDialogPane().getStyleClass().contains("theme-dark"));
            assertEquals(3, d.getDialogPane().lookupAll(".step").size());
        });
    }

    @Test
    void stage2ValidatesTheEditedDigestLive() {
        Fx.run(() -> {
            Dialog<PromptIntentDigest> d = meta.stage2Dialog("{\"targetObjective\": \"Summarise\"}");
            Label check = byId(d, "meta-digest-check");
            assertTrue(check.getText().startsWith("✓"), check.getText());
            assertFalse(UiComponentsTest.run(d).isDisabled());

            TextArea digest = byId(d, "meta-digest");
            digest.setText("{\"targetObjective\": ");
            assertTrue(check.getText().startsWith("✗"), check.getText());
            assertTrue(UiComponentsTest.run(d).isDisabled(), "Stage 2 must not start from broken JSON");

            digest.setText("{\"targetObjective\": \"Classify\", \"extra\": 1}");
            confirm(d);
            assertEquals("Classify", d.getResult().targetObjective());
        });
    }

    @Test
    void digestProblems() {
        assertTrue(MetaPromptFlow.digestProblem("{\"targetObjective\":\"x\"}").isEmpty());
        assertEquals("The digest is empty.", MetaPromptFlow.digestProblem(" ").orElseThrow());
        assertEquals("\"targetObjective\" is required.", MetaPromptFlow.digestProblem("{}").orElseThrow());
        assertTrue(MetaPromptFlow.digestProblem("not json").isPresent());
        assertTrue(MetaPromptFlow.digestProblem("{\"targetObjective\": [1,2]").isPresent());

        String broken = MetaPromptFlow.digestProblem("{\n  \"targetObjective\": \"x\",\n").orElseThrow();
        assertTrue(broken.startsWith("Line 3, column 1: Unexpected end-of-input"), broken);
        assertFalse(broken.contains("REDACTED") || broken.contains("Source"), "no parser internals: " + broken);
    }

    @Test
    void stage3ShowsTheCompiledPromptReadOnlyAndReturnsTheSample() {
        Fx.run(() -> {
            CompiledPromptSpec spec = new CompiledPromptSpec("directive", "constraints", "{}", "{}", "edge", "ASSEMBLED");
            Dialog<String> d = meta.stage3Dialog(spec, 2);
            TextArea preview = byId(d, "meta-preview");
            assertEquals("ASSEMBLED", preview.getText());
            assertFalse(preview.isEditable());
            assertTrue(d.getDialogPane().lookupAll(".studio-hint").stream()
                    .anyMatch(n -> ((Label) n).getText().contains("2 persona sentence")));
            ((TextArea) byId(d, "meta-sample")).setText("payload");
            confirm(d);
            assertEquals("payload", d.getResult());
        });
    }
}
