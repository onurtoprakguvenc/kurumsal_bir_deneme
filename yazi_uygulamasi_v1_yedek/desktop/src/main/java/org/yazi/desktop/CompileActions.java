package org.yazi.desktop;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The Compile menu: turns the selection (or the paragraph at the caret) into a video or image prompt. Results go
 * to the side panel; the document is never changed.
 *
 * <p>Video runs the ported video engine locally (no model call, no tokens). Image runs the ported image pipeline
 * on the shared gateway.</p>
 */
final class CompileActions {

    private final Window owner;
    private final EditorPane editor;
    private final KeyedGateway gateway;
    private final OperationController.View view;
    private final BooleanSupplier ensureKey;
    private final PromptPipelineOrchestrator video = new PromptPipelineOrchestrator();
    private final VisualPipeline image;
    private final MetaPromptFlow metaPrompt;
    private volatile boolean busy;

    CompileActions(Window owner, EditorPane editor, KeyedGateway gateway, OperationController.View view,
                   BooleanSupplier ensureKey) {
        this.owner = owner;
        this.editor = editor;
        this.gateway = gateway;
        this.view = view;
        this.ensureKey = ensureKey;
        this.image = new VisualPipeline(gateway);
        // GEMINI_MODEL is the setting prompt_gelistirme read; its default is kept.
        this.metaPrompt = new MetaPromptFlow(owner, new MetaPromptPipeline(gateway, System.getenv("GEMINI_MODEL")), view);
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
        Optional<RawUserPromptInput> input = videoDialog(sourceText());
        if (input.isEmpty()) {
            return;
        }
        busy = true;
        view.status("Compiling video prompt…");
        Thread.ofVirtual().name("yazi-video").start(() -> {
            PipelineResult result = video.run(input.get());
            Platform.runLater(() -> {
                busy = false;
                boolean ok = result instanceof PipelineResult.Success;
                showOutput(ok ? "Video prompt" : "Video prompt failed", formatVideo(result));
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

    private Optional<RawUserPromptInput> videoDialog(String source) {
        TextArea description = area(source, 6);
        Spinner<Integer> duration = new Spinner<>(1, 60, 10);
        duration.setEditable(true);
        ComboBox<AspectRatio> aspect = new ComboBox<>();
        aspect.getItems().setAll(AspectRatio.values());
        aspect.setValue(AspectRatio.RATIO_16_9);
        ComboBox<String> preset = new ComboBox<>();
        preset.getItems().add("(inferred)");
        for (VisualStylePreset p : VisualStylePreset.values()) {
            preset.getItems().add(p.name());
        }
        preset.setValue("(inferred)");
        ComboBox<ImperfectionLevel> imperfection = new ComboBox<>();
        imperfection.getItems().setAll(ImperfectionLevel.values());
        imperfection.setValue(ImperfectionLevel.OFF);
        TextField suppress = new TextField();
        suppress.setPromptText("optional, e.g. sky, ground");

        GridPane grid = grid();
        grid.addRow(0, new Label("Scene"), description);
        grid.addRow(1, new Label("Duration (s)"), duration);
        grid.addRow(2, new Label("Aspect ratio"), aspect);
        grid.addRow(3, new Label("Style preset"), preset);
        grid.addRow(4, new Label("Imperfection"), imperfection);
        grid.addRow(5, new Label("Suppress detail"), suppress);

        return this.<RawUserPromptInput>dialog("Video prompt", "Compile", grid, () -> {
            VisualStylePreset chosen = preset.getValue().startsWith("(") ? null : VisualStylePreset.valueOf(preset.getValue());
            return videoInput(description.getText(), duration.getValue(), aspect.getValue(), chosen,
                    imperfection.getValue(), suppress.getText());
        });
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
        Optional<VisualRequest> request = imageDialog(sourceText());
        if (request.isEmpty()) {
            return;
        }
        busy = true;
        view.status("Compiling image prompt…");
        Thread.ofVirtual().name("yazi-image").start(() -> {
            try {
                VisualPipeline.VisualResult result = image.compile(request.get(), null);
                Platform.runLater(() -> {
                    busy = false;
                    showOutput("Image prompt", formatImage(result));
                    view.cost(result.usage(), request.get().rawScene().length() + request.get().slotTwo().length());
                    view.status("Image prompt compiled.");
                });
            } catch (GatewayException | RuntimeException e) {
                Platform.runLater(() -> {
                    busy = false;
                    view.status("Image prompt failed: " + e.getMessage());
                });
            }
        });
    }

    private Optional<VisualRequest> imageDialog(String source) {
        ComboBox<IngestionMode> mode = new ComboBox<>();
        mode.getItems().setAll(IngestionMode.values());
        mode.setValue(IngestionMode.DIRECT);
        TextArea scene = area(source, 5);
        TextArea slotOne = area("", 2);
        slotOne.setPromptText("Slot 1: environment / spatial (location, era, light)");
        TextArea slotTwo = area(source, 3);
        slotTwo.setPromptText("Slot 2: dramatic action (mandatory)");
        TextArea slotThree = area("", 2);
        slotThree.setPromptText("Slot 3: optional directives (lens, elevation, --ar, engine flags)");
        VBox slots = new VBox(6, slotOne, slotTwo, slotThree);

        ComboBox<PromptCompiler.EngineProfile> engine = new ComboBox<>();
        engine.getItems().setAll(PromptCompiler.EngineProfile.values());
        engine.setValue(PromptCompiler.EngineProfile.MIDJOURNEY_V6);
        ComboBox<GeminiModel> model = new ComboBox<>();
        model.getItems().setAll(GeminiModel.values());
        model.setValue(GeminiModel.FLASH);
        TextField aspect = new TextField(VisualRequest.DEFAULT_ASPECT_RATIO);
        TextField flags = new TextField(VisualRequest.DEFAULT_FLAGS);
        CheckBox regional = new CheckBox("Regional inpainting passes");
        regional.setSelected(true);

        GridPane grid = grid();
        grid.addRow(0, new Label("Mode"), mode);
        grid.addRow(1, new Label("Scene"), scene);
        grid.addRow(2, new Label("Slots"), slots);
        grid.addRow(3, new Label("Engine"), engine);
        grid.addRow(4, new Label("Model"), model);
        grid.addRow(5, new Label("Aspect ratio"), aspect);
        grid.addRow(6, new Label("Midjourney flags"), flags);
        grid.addRow(7, new Label(""), regional);
        Runnable toggle = () -> {
            boolean staged = mode.getValue() == IngestionMode.THREE_STAGE;
            scene.setDisable(staged);
            slots.setDisable(!staged);
        };
        mode.valueProperty().addListener((obs, old, v) -> toggle.run());
        toggle.run();

        return this.<VisualRequest>dialog("Image prompt", "Compile", grid, () -> new VisualRequest(
                mode.getValue(), scene.getText(), slotOne.getText(), slotTwo.getText(), slotThree.getText(),
                engine.getValue(), model.getValue(), aspect.getText(), flags.getText(), regional.isSelected()));
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

    /** The selection, or the paragraph that ends at the caret. */
    private String sourceText() {
        BufferSnapshot s = editor.snapshot();
        if (s.hasSelection()) {
            return s.selectedText().strip();
        }
        return BlockLocator.precedingBlock(s.text(), s.caret()).map(s::slice).orElse("").strip();
    }

    private void showOutput(String title, String text) {
        view.answerStarted(title);
        view.answerFinished(text, false);
    }

    private <T> Optional<T> dialog(String title, String action, Node content, java.util.function.Supplier<T> result) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        ButtonType ok = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ok ? result.get() : null);
        return dialog.showAndWait();
    }

    private static TextArea area(String text, int rows) {
        TextArea area = new TextArea(text);
        area.setWrapText(true);
        area.setPrefRowCount(rows);
        area.setPrefColumnCount(48);
        return area;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        GridPane.setHgrow(grid, Priority.ALWAYS);
        return grid;
    }
}
