package org.yazi.desktop;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.yazi.gateway.GatewayException;
import org.yazi.metaprompt.MetaPromptContracts.CompiledPromptSpec;
import org.yazi.metaprompt.MetaPromptContracts.PromptIntentDigest;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationReport;
import org.yazi.metaprompt.MetaPromptPipeline;
import org.yazi.metaprompt.PatchMerger;
import org.yazi.metaprompt.PersonaLinter;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The three meta-prompt stages as a guided flow. Between stages the writer sees (and for Stage 1 may edit) the
 * intermediate record; nothing is carried between runs. Results are shown in the side panel.
 */
final class MetaPromptFlow {

    /** Pretty output for the writer; reading is tolerant like the original server. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Window owner;
    private final MetaPromptPipeline pipeline;
    private final OperationController.View view;
    private volatile boolean busy;

    MetaPromptFlow(Window owner, MetaPromptPipeline pipeline, OperationController.View view) {
        this.owner = owner;
        this.pipeline = pipeline;
        this.view = view;
    }

    void start(String selection) {
        if (busy) {
            return;
        }
        TextArea concept = area(selection, 8);
        Optional<String> raw = ask("Meta-prompt — Stage 1: decompose", "Raw concept to decompose:", concept,
                "Run Stage 1", concept::getText);
        raw.filter(s -> !s.isBlank()).ifPresent(this::runStage1);
    }

    // ------------------------------------------------------------------------------------------------

    private void runStage1(String rawConcept) {
        background("Stage 1: decomposing…", () -> pipeline.stage1(rawConcept, null), r -> {
            view.cost(r.usage(), rawConcept.length());
            show("Meta-prompt: digest", json(r.digest()));
            TextArea digest = area(json(r.digest()), 16);
            ask("Meta-prompt — Stage 2: compile", "Intent digest (edit if needed):", digest, "Run Stage 2",
                    () -> digest.getText())
                    .flatMap(this::parseDigest)
                    .ifPresent(this::runStage2);
        });
    }

    private void runStage2(PromptIntentDigest digest) {
        background("Stage 2: compiling…", () -> pipeline.stage2(digest, null), r -> {
            view.cost(r.usage(), 0);
            String removed = findings(r.personaRemoved());
            show("Meta-prompt: compiled spec", r.spec().assembledPrompt() + removed + "\n\n" + json(r.spec()));

            TextArea preview = area(r.spec().assembledPrompt(), 10);
            preview.setEditable(false);
            TextArea sample = area("", 3);
            sample.setPromptText("Optional sample payload. Leave empty to let Stage 3 generate an adversarial one.");
            VBox content = new VBox(8, preview, new Label("Sample payload (optional):"), sample);
            if (!removed.isBlank()) {
                content.getChildren().add(1, new Label(r.personaRemoved().size() + " persona sentence(s) removed."));
            }
            ask("Meta-prompt — Stage 3: verify & harden", "Assembled prompt:", content, "Run Stage 3",
                    sample::getText)
                    .ifPresent(payload -> runStage3(digest, r.spec(), payload));
        });
    }

    private void runStage3(PromptIntentDigest digest, CompiledPromptSpec spec, String samplePayload) {
        var request = MetaPromptPipeline.verificationRequest(digest, spec, samplePayload);
        background("Stage 3: verifying…", () -> pipeline.stage3(request, null), r -> {
            view.cost(r.usage(), request.compiledPrompt().length());
            PromptVerificationReport report = r.report();
            List<String> patches = report.hardeningPatches() == null ? List.of() : report.hardeningPatches();

            if (!patches.isEmpty() && confirm(patches.size() + " hardening patch(es) proposed:\n\n"
                    + String.join("\n", patches) + "\n\nMerge them into the prompt and the manifest?")) {
                PatchMerger.Merged merged = PatchMerger.apply(spec, report);
                report = merged.report();
            }
            StringBuilder out = new StringBuilder("DEPLOYMENT MANIFEST\n").append(report.deploymentManifest())
                    .append("\n\nSCORE ").append(report.structuralIntegrityScore())
                    .append("\n\nRISKS\n- ").append(String.join("\n- ", nonNull(report.boundaryBreachRisks())))
                    .append("\n\nPATCHES\n- ").append(String.join("\n- ", nonNull(report.hardeningPatches())));
            if (r.dynamicInputRebuilt()) {
                out.append("\n\n[<dynamic_input> rebuilt from requiredInputs");
                if (!r.invalidTokens().isEmpty()) {
                    out.append("; invalid tokens replaced: ").append(String.join(", ", r.invalidTokens()));
                }
                out.append(']');
            }
            out.append(findings(r.personaRemoved())).append("\n\n").append(json(report));
            show("Meta-prompt: deployment manifest", out.toString());
            view.status("Meta-prompt complete.");
        });
    }

    // ------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Stage<T> {
        T run() throws GatewayException;
    }

    private <T> void background(String message, Stage<T> stage, Consumer<T> onDone) {
        busy = true;
        view.status(message);
        Thread.ofVirtual().name("yazi-metaprompt").start(() -> {
            try {
                T result = stage.run();
                Platform.runLater(() -> {
                    busy = false;
                    onDone.accept(result);
                });
            } catch (GatewayException | RuntimeException e) {
                Platform.runLater(() -> {
                    busy = false;
                    view.status("Meta-prompt stopped: " + e.getMessage());
                });
            }
        });
    }

    private Optional<PromptIntentDigest> parseDigest(String text) {
        try {
            return Optional.of(JSON.readValue(text, PromptIntentDigest.class));
        } catch (Exception e) {
            view.status("The edited digest is not valid JSON: " + e.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> ask(String title, String header, javafx.scene.Node content, String action,
                                java.util.function.Supplier<T> result) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setResizable(true);
        VBox box = new VBox(content);
        box.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(box);
        ButtonType ok = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ok ? result.get() : null);
        return dialog.showAndWait();
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setResizable(true);
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void show(String title, String text) {
        view.answerStarted(title);
        view.answerFinished(text, false);
    }

    private static String findings(List<PersonaLinter.Finding> removed) {
        if (removed.isEmpty()) {
            return "";
        }
        return "\n\n[persona sentences removed]\n" + removed.stream()
                .map(f -> "- " + f.field() + ": " + f.removed())
                .collect(Collectors.joining("\n"));
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static TextArea area(String text, int rows) {
        TextArea area = new TextArea(text);
        area.setWrapText(true);
        area.setPrefRowCount(rows);
        area.setPrefColumnCount(64);
        return area;
    }
}
