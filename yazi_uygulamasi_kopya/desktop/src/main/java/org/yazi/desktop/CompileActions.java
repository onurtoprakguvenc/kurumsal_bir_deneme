package org.yazi.desktop;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.yazi.gateway.GatewayException;
import org.yazi.metaprompt.MetaPromptPipeline;
import org.yazi.model.BufferSnapshot;
import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.StylePreferences;
import org.yazi.motion.domain.VisualStylePreset;
import org.yazi.motion.json.PromptPackageJson;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;
import org.yazi.text.BlockLocator;
import org.yazi.visual.GeminiModel;
import org.yazi.visual.IngestionMode;
import org.yazi.visual.PromptCompiler;
import org.yazi.visual.VisualPipeline;
import org.yazi.visual.VisualRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The Compile menu: turns the selection (or the paragraph at the caret) into a video, image or meta prompt.
 * Results go to the side panel; the document is never changed.
 *
 * <p>Video runs the ported video engine locally (no model call, no tokens). Image runs the ported image pipeline
 * on the shared gateway. Both dialogs are {@link StudioDialog}s: Ctrl+Enter compiles, Esc cancels, Alt + the
 * underlined letter jumps to a field, and Compile stays disabled until the input is usable. The dialogs build
 * exactly the same engine inputs as before ({@link #videoInput}, {@link VisualRequest}).</p>
 */
final class CompileActions {

    private final Window owner;
    private final EditorPane editor;
    private final KeyedGateway gateway;
    private final OperationController.View view;
    private final BooleanSupplier ensureKey;
    private final Supplier<UiSettings.Theme> theme;
    private final PromptPipelineOrchestrator video = new PromptPipelineOrchestrator();
    private final VisualPipeline image;
    private final MetaPromptFlow metaPrompt;
    private volatile boolean busy;

    CompileActions(Window owner, EditorPane editor, KeyedGateway gateway, OperationController.View view,
                   BooleanSupplier ensureKey, Supplier<UiSettings.Theme> theme) {
        this.owner = owner;
        this.editor = editor;
        this.gateway = gateway;
        this.view = view;
        this.ensureKey = ensureKey;
        this.theme = theme;
        this.image = new VisualPipeline(gateway);
        // GEMINI_MODEL is the setting prompt_gelistirme read; its default is kept.
        this.metaPrompt = new MetaPromptFlow(owner, new MetaPromptPipeline(gateway, System.getenv("GEMINI_MODEL")),
                view, theme);
    }

    // ------------------------------------------------------------------------------------------------
    // Meta-prompt
    // ------------------------------------------------------------------------------------------------

    void compileMetaPrompt() {
        if (!gateway.hasKey() && !ensureKey.getAsBoolean()) {
            view.status("Meta-prompts need an API key (File → API key…).");
            return;
        }
        metaPrompt.start(sourceText());
    }

    // ------------------------------------------------------------------------------------------------
    // Video
    // ------------------------------------------------------------------------------------------------

    void compileVideo() {
        if (busy) {
            return;
        }
        Optional<RawUserPromptInput> input = videoDialog(sourceText()).showAndWait();
        if (input.isEmpty()) {
            return;
        }
        busy = true;
        view.activity(OperationController.Activity.WORKING);
        view.status("Compiling video prompt…");
        Thread.ofVirtual().name("yazi-video").start(() -> {
            PipelineResult result;
            try {
                result = video.run(input.get());
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    busy = false;
                    view.activity(OperationController.Activity.ERROR);
                    view.status("Video prompt failed: " + message(e));
                });
                return;
            }
            Platform.runLater(() -> {
                busy = false;
                boolean ok = result instanceof PipelineResult.Success;
                view.showOutput(ok ? "Video prompt" : "Video prompt failed", formatVideo(result));
                view.activity(ok ? OperationController.Activity.IDLE : OperationController.Activity.ERROR);
                view.status(ok ? "Video prompt compiled locally (0 tokens)."
                        : "Video prompt failed: " + ((PipelineResult.Failure) result).errorCode());
            });
        });
    }

    /** The video engine input the dialog produces; {@code preset} null means "inferred from the scene". */
    static RawUserPromptInput videoInput(String description, int durationSec, AspectRatio aspect,
                                         VisualStylePreset preset, ImperfectionLevel imperfection, String suppress) {
        return new RawUserPromptInput(UUID.randomUUID().toString(), description, durationSec, aspect, List.of(),
                new StylePreferences(preset, null, null, null, null, null),
                (suppress == null || suppress.isBlank()) ? null : suppress,
                imperfection, null);
    }

    /** Side-panel text for a video result. */
    static String formatVideo(PipelineResult result) {
        return switch (result) {
            case PipelineResult.Success s -> {
                var prompts = s.promptPackage().compiledPrompts();
                StringBuilder out = new StringBuilder()
                        .append("POSITIVE\n").append(prompts.positivePromptSummary()).append("\n\n")
                        .append("NEGATIVE\n").append(prompts.negativePromptSummary()).append("\n\n")
                        .append("TIMELINE\n").append(prompts.temporalBreakdownText()).append("\n\n");
                if (!s.warnings().isEmpty()) {
                    out.append("WARNINGS\n- ").append(String.join("\n- ", s.warnings())).append("\n\n");
                }
                yield out.append("PROMPT PACKAGE (JSON)\n").append(PromptPackageJson.toJson(result, true)).toString();
            }
            case PipelineResult.Failure f -> f.errorCode() + ": " + f.message()
                    + "\n\n" + PromptPackageJson.toJson(result, true);
        };
    }

    /** "Cinematic 35 mm", "MVT Cyberpunk"...; unknown future presets fall back to title case. */
    static String presetLabel(VisualStylePreset preset) {
        if (preset == null) {
            return "Inferred from the scene";
        }
        return switch (preset.name()) {
            case "CINEMATIC_35MM" -> "Cinematic 35 mm";
            case "HYPERREALISTIC_8K" -> "Hyperrealistic 8K";
            case "MVT_CYBERPUNK" -> "MVT Cyberpunk";
            case "NEO_NOIR" -> "Neo-noir";
            default -> titleCase(preset.name());
        };
    }

    static String titleCase(String constant) {
        String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** Built but not shown; package-private so tests can fill it in and read the result. */
    Dialog<RawUserPromptInput> videoDialog(String source) {
        TextArea description = area(source, 6);
        description.setId("video-scene");
        Label sceneCount = wordCounter(description);

        Slider duration = new Slider(1, 60, 10);
        duration.setId("video-duration");
        duration.setBlockIncrement(1);
        duration.setMajorTickUnit(10);
        duration.setMinorTickCount(9);
        duration.setShowTickMarks(true);
        duration.setSnapToTicks(true);
        HBox.setHgrow(duration, Priority.ALWAYS);
        Label durationValue = new Label();
        durationValue.setMinWidth(44);
        durationValue.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(duration.getValue()) + " s", duration.valueProperty()));
        HBox durationRow = new HBox(10, duration, durationValue);

        Segmented<AspectRatio> aspect = new Segmented<>(List.of(AspectRatio.values()), AspectRatio::label,
                AspectRatio.RATIO_16_9).id("video-aspect");

        List<VisualStylePreset> presets = new ArrayList<>();
        presets.add(null);
        presets.addAll(Arrays.asList(VisualStylePreset.values()));
        ComboBox<VisualStylePreset> preset = new ComboBox<>();
        preset.setId("video-preset");
        preset.getItems().setAll(presets);
        preset.setConverter(new StringConverter<>() {
            @Override
            public String toString(VisualStylePreset p) {
                return presetLabel(p);
            }

            @Override
            public VisualStylePreset fromString(String s) {
                return null;
            }
        });
        preset.setButtonCell(presetCell());
        preset.setCellFactory(list -> presetCell());
        preset.setValue(null);
        preset.setMaxWidth(Double.MAX_VALUE);

        Segmented<ImperfectionLevel> imperfection = new Segmented<>(List.of(ImperfectionLevel.values()),
                l -> titleCase(l.name()), ImperfectionLevel.OFF).id("video-imperfection");
        imperfection.tooltip(ImperfectionLevel.LIGHT, "A touch of organic, filmed imperfection")
                .tooltip(ImperfectionLevel.PRONOUNCED, "Clearly handheld, analogue character");

        TextField suppress = new TextField();
        suppress.setId("video-suppress");
        suppress.setPromptText("e.g. sky, ground");

        BooleanBinding valid = Bindings.createBooleanBinding(() -> !description.getText().isBlank(),
                description.textProperty());
        return new StudioDialog<RawUserPromptInput>("▶", "Video prompt",
                "Turns a scene into a shot-by-shot prompt package with a timeline and a negative prompt.", "Compile")
                .badge("Local · 0 tokens", true)
                .section("Scene")
                .field("_Scene", description, sceneCount)
                .section("Shot")
                .field("_Duration", durationRow)
                .field("_Aspect ratio", aspect.view())
                .section("Look")
                .field("Style _preset", preset)
                .field("_Imperfection", imperfection.view())
                .field("S_uppress detail", suppress, "Elements to keep plain or out of focus.")
                .validWhen(valid, constant("Describe the scene to compile."))
                .result(() -> videoInput(description.getText(), (int) Math.round(duration.getValue()),
                        aspect.getValue(), preset.getValue(), imperfection.getValue(), suppress.getText()))
                .focus(description)
                .build(owner, theme.get());
    }

    // ------------------------------------------------------------------------------------------------
    // Image
    // ------------------------------------------------------------------------------------------------

    void compileImage() {
        if (busy) {
            return;
        }
        if (!gateway.hasKey() && !ensureKey.getAsBoolean()) {
            view.status("Image prompts need an API key (File → API key…).");
            return;
        }
        Optional<VisualRequest> request = imageDialog(sourceText()).showAndWait();
        if (request.isEmpty()) {
            return;
        }
        busy = true;
        view.activity(OperationController.Activity.WORKING);
        view.status("Compiling image prompt…");
        Thread.ofVirtual().name("yazi-image").start(() -> {
            try {
                VisualPipeline.VisualResult result = image.compile(request.get(), null);
                Platform.runLater(() -> {
                    busy = false;
                    view.showOutput("Image prompt", formatImage(result));
                    view.cost(result.usage(), request.get().rawScene().length() + request.get().slotTwo().length());
                    view.activity(OperationController.Activity.IDLE);
                    view.status("Image prompt compiled.");
                });
            } catch (GatewayException e) {
                failed("Image prompt failed: " + OperationController.describe(e));
            } catch (RuntimeException e) {
                failed("Image prompt failed: " + message(e));
            }
        });
    }

    /** One-line description of each ingestion mode, shown under the mode switch. */
    static String modeHint(IngestionMode mode) {
        return switch (mode) {
            case DIRECT -> "The text is the scene. One model call.";
            case NARRATIVE -> "The text is a sequence; one keyframe is isolated first. Two model calls.";
            case THREE_STAGE -> "Environment, action and directives are assembled locally, then structured.";
        };
    }

    /** Built but not shown; package-private so tests can fill it in and read the result. */
    Dialog<VisualRequest> imageDialog(String source) {
        Segmented<IngestionMode> mode = new Segmented<>(List.of(IngestionMode.values()),
                m -> m == IngestionMode.THREE_STAGE ? "Three-stage" : titleCase(m.name()), IngestionMode.DIRECT)
                .id("image-mode");
        Label modeHint = StudioDialog.hintLabel("");
        modeHint.textProperty().bind(Bindings.createStringBinding(() -> modeHint(mode.getValue()),
                mode.valueProperty()));

        TextArea scene = area(source, 5);
        scene.setId("image-scene");
        TextArea slotOne = area("", 2);
        slotOne.setId("image-slot1");
        slotOne.setPromptText("Environment / spatial: location, era, light");
        TextArea slotTwo = area(source, 3);
        slotTwo.setId("image-slot2");
        slotTwo.setPromptText("Dramatic action (required)");
        TextArea slotThree = area("", 2);
        slotThree.setId("image-slot3");
        slotThree.setPromptText("Optional directives: lens, elevation, --ar, engine flags");
        VBox slots = new VBox(6, slotOne, slotTwo, slotThree);

        BooleanBinding staged = Bindings.createBooleanBinding(() -> mode.getValue() == IngestionMode.THREE_STAGE,
                mode.valueProperty());
        VBox sceneBox = new VBox(3, scene, wordCounter(scene));
        sceneBox.visibleProperty().bind(staged.not());
        sceneBox.managedProperty().bind(staged.not());
        slots.visibleProperty().bind(staged);
        slots.managedProperty().bind(staged);
        VBox sourceBox = new VBox(0, sceneBox, slots);

        Segmented<PromptCompiler.EngineProfile> engine = new Segmented<>(
                List.of(PromptCompiler.EngineProfile.values()), PromptCompiler.EngineProfile::getDisplayName,
                PromptCompiler.EngineProfile.MIDJOURNEY_V6).id("image-engine");
        ComboBox<GeminiModel> model = new ComboBox<>();
        model.setId("image-model");
        model.getItems().setAll(GeminiModel.values());
        model.setValue(GeminiModel.FLASH);
        model.setMaxWidth(Double.MAX_VALUE);
        model.setConverter(new StringConverter<>() {
            @Override
            public String toString(GeminiModel m) {
                return m == null ? "" : m.getLabel();
            }

            @Override
            public GeminiModel fromString(String s) {
                return null;
            }
        });
        TextField aspect = new TextField(VisualRequest.DEFAULT_ASPECT_RATIO);
        aspect.setId("image-aspect");
        aspect.setPrefColumnCount(8);
        TextField flags = new TextField(VisualRequest.DEFAULT_FLAGS);
        flags.setId("image-flags");
        flags.disableProperty().bind(Bindings.createBooleanBinding(
                () -> engine.getValue() != PromptCompiler.EngineProfile.MIDJOURNEY_V6, engine.valueProperty()));
        CheckBox regional = new CheckBox("Regional inpainting passes");
        regional.setId("image-regional");
        regional.setSelected(true);

        BooleanBinding valid = Bindings.createBooleanBinding(
                () -> staged.get() ? !slotTwo.getText().isBlank() : !scene.getText().isBlank(),
                staged, scene.textProperty(), slotTwo.textProperty());
        StringBinding problem = Bindings.createStringBinding(
                () -> staged.get() ? "The dramatic action (slot 2) is required." : "Describe the scene to compile.",
                staged);
        return new StudioDialog<VisualRequest>("◩", "Image prompt",
                "Structures a scene into a render-ready prompt for Midjourney or Flux, with optional regional passes.",
                "Compile")
                .badge("Uses the model", false)
                .section("Source")
                .field("_Mode", mode.view(), modeHint)
                .field("_Scene", sourceBox)
                .section("Render")
                .field("_Engine", engine.view())
                .field("M_odel", model)
                .field("_Aspect ratio", aspect)
                .field("_Flags", flags, "Midjourney only, without --ar.")
                .field("", regional)
                .validWhen(valid, problem)
                .result(() -> new VisualRequest(mode.getValue(), scene.getText(), slotOne.getText(), slotTwo.getText(),
                        slotThree.getText(), engine.getValue(), model.getValue(), aspect.getText(), flags.getText(),
                        regional.isSelected()))
                .focus(scene)
                .build(owner, theme.get());
    }

    /** Side-panel text for an image result. */
    static String formatImage(VisualPipeline.VisualResult r) {
        StringBuilder out = new StringBuilder("PROMPT (").append(r.output().profile().getDisplayName()).append(")\n")
                .append(r.output().clipboardText()).append("\n\n");
        if (r.output().negativePrompt() != null && !r.output().negativePrompt().isBlank()) {
            out.append("NEGATIVE\n").append(r.output().negativePrompt()).append("\n\n");
        }
        if (r.regionalManifest() != null) {
            out.append(r.regionalManifest()).append("\n\n");
        }
        if (!r.logs().isEmpty()) {
            out.append("NOTES\n- ").append(String.join("\n- ", r.logs())).append("\n\n");
        }
        return out.append(r.structuralReport()).toString();
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private void failed(String message) {
        Platform.runLater(() -> {
            busy = false;
            view.activity(OperationController.Activity.ERROR);
            view.status(message);
        });
    }

    /** The selection, or the paragraph that ends at the caret. */
    private String sourceText() {
        BufferSnapshot s = editor.snapshot();
        if (s.hasSelection()) {
            return s.selectedText().strip();
        }
        return BlockLocator.precedingBlock(s.text(), s.caret()).map(s::slice).orElse("").strip();
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static Label wordCounter(TextArea area) {
        Label count = StudioDialog.hintLabel("");
        count.textProperty().bind(Bindings.createStringBinding(() -> {
            DocumentStats s = DocumentStats.of(area.getText());
            return String.format("%,d %s · %,d characters", s.words(), s.words() == 1 ? "word" : "words",
                    s.characters());
        }, area.textProperty()));
        return count;
    }

    private static ListCell<VisualStylePreset> presetCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(VisualStylePreset item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : presetLabel(item));
            }
        };
    }

    private static javafx.beans.value.ObservableStringValue constant(String text) {
        return new javafx.beans.property.ReadOnlyStringWrapper(text);
    }

    private static TextArea area(String text, int rows) {
        TextArea area = new TextArea(text);
        area.setWrapText(true);
        area.setPrefRowCount(rows);
        area.setPrefColumnCount(48);
        return area;
    }
}
