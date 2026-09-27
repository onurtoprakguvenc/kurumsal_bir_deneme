package org.yazi.desktop;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The three meta-prompt stages as a guided flow. Every stage dialog shows the stepper
 * "Decompose → Compile → Verify". Between stages the writer sees (and for Stage 1 may edit) the intermediate
 * record; the edited digest is validated live, so Stage 2 cannot start from broken JSON. Nothing is carried
 * between runs. Results are shown in the side panel.
 */
final class MetaPromptFlow {

    static final List<String> STEPS = List.of("Decompose", "Compile", "Verify");

    /** Pretty output for the writer; reading is tolerant like the original server. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Window owner;
    private final MetaPromptPipeline pipeline;
    private final OperationController.View view;
    private final Supplier<UiSettings.Theme> theme;
    private volatile boolean busy;

    MetaPromptFlow(Window owner, MetaPromptPipeline pipeline, OperationController.View view,
                   Supplier<UiSettings.Theme> theme) {
        this.owner = owner;
        this.pipeline = pipeline;
        this.view = view;
        this.theme = theme;
    }

    void start(String selection) {
        if (busy) {
            return;
        }
        stage1Dialog(selection).showAndWait().filter(s -> !s.isBlank()).ifPresent(this::runStage1);
    }

    // ------------------------------------------------------------------------------------------------
    // Dialogs (built, not shown: tests fill them in)
    // ------------------------------------------------------------------------------------------------

    Dialog<String> stage1Dialog(String selection) {
        TextArea concept = area(selection, 8);
        concept.setId("meta-concept");
        BooleanBinding valid = Bindings.createBooleanBinding(() -> !concept.getText().isBlank(),
                concept.textProperty());
        return new StudioDialog<String>("✦", "Meta-prompt",
                "Decompose a raw concept into an intent digest: objective, inputs, constraints.", "Run stage 1")
                .badge("Uses the model", false)
                .stepper(STEPS, 0)
                .field("Raw _concept", concept, CompileActions.wordCounter(concept))
                .validWhen(valid, new javafx.beans.property.ReadOnlyStringWrapper("Describe the concept first."))
                .result(concept::getText)
                .focus(concept)
                .build(owner, theme.get());
    }

    Dialog<PromptIntentDigest> stage2Dialog(String digestJson) {
        TextArea digest = area(digestJson, 16);
        digest.setId("meta-digest");
        digest.getStyleClass().add(SidePanel.MONOSPACE);
        Label check = StudioDialog.hintLabel("");
        check.setId("meta-digest-check");
        BooleanBinding valid = Bindings.createBooleanBinding(() -> digestProblem(digest.getText()).isEmpty(),
                digest.textProperty());
        check.textProperty().bind(Bindings.createStringBinding(
                () -> digestProblem(digest.getText()).map(p -> "✗ " + p).orElse("✓ Valid intent digest"),
                digest.textProperty()));
        return new StudioDialog<PromptIntentDigest>("✦", "Meta-prompt",
                "Review the intent digest. Edit anything the model got wrong, then compile it into a prompt.",
                "Run stage 2")
                .badge("Uses the model", false)
                .stepper(STEPS, 1)
                .field("_Digest", digest, check)
                .validWhen(valid, new javafx.beans.property.ReadOnlyStringWrapper("Fix the digest to continue."))
                .result(() -> parseDigest(digest.getText()).orElse(null))
                .focus(digest)
                .build(owner, theme.get());
    }

    Dialog<String> stage3Dialog(CompiledPromptSpec spec, int personaRemoved) {
        TextArea preview = area(spec.assembledPrompt(), 10);
        preview.setId("meta-preview");
        preview.setEditable(false);
        preview.getStyleClass().add(SidePanel.MONOSPACE);
        TextArea sample = area("", 3);
        sample.setId("meta-sample");
        sample.setPromptText("Leave empty to let Stage 3 generate an adversarial payload.");
        String note = personaRemoved == 0 ? null
                : personaRemoved + " persona sentence(s) were removed from the compiled prompt.";
        return new StudioDialog<String>("✦", "Meta-prompt",
                "Stress-test the compiled prompt and harden it into a deployment manifest.", "Run stage 3")
                .badge("Uses the model", false)
                .stepper(STEPS, 2)
                .field("_Prompt", preview, note)
                .field("_Sample payload", sample, "Optional.")
                .result(sample::getText)
                .focus(sample)
                .build(owner, theme.get());
    }

    /** Empty when {@code json} is a usable digest; otherwise a short reason. */
    static Optional<String> digestProblem(String json) {
        if (json == null || json.isBlank()) {
            return Optional.of("The digest is empty.");
        }
        try {
            PromptIntentDigest d = JSON.readValue(json, PromptIntentDigest.class);
            if (d == null || d.targetObjective() == null || d.targetObjective().isBlank()) {
                return Optional.of("\"targetObjective\" is required.");
            }
            return Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.of(readable(e));
        }
    }

    /** "Line 4, column 1: Unexpected end-of-input" instead of Jackson's internal source description. */
    static String readable(JsonProcessingException e) {
        String msg = e.getOriginalMessage() == null ? "Not valid JSON." : e.getOriginalMessage();
        int cut = msg.indexOf(" (start marker");
        if (cut < 0) {
            cut = msg.indexOf('\n');
        }
        if (cut > 0) {
            msg = msg.substring(0, cut);
        }
        msg = msg.strip();
        if (msg.length() > 120) {
            msg = msg.substring(0, 120) + "…";
        }
        JsonLocation at = e.getLocation();
        return (at != null && at.getLineNr() > 0)
                ? "Line " + at.getLineNr() + ", column " + at.getColumnNr() + ": " + msg
                : msg;
    }

    // ------------------------------------------------------------------------------------------------

    private void runStage1(String rawConcept) {
        background("Stage 1 of 3: decomposing…", () -> pipeline.stage1(rawConcept, null), r -> {
            view.cost(r.usage(), rawConcept.length());
            view.showOutput("Meta-prompt: digest", json(r.digest()));
            stage2Dialog(json(r.digest())).showAndWait().ifPresent(this::runStage2);
        });
    }

    private void runStage2(PromptIntentDigest digest) {
        background("Stage 2 of 3: compiling…", () -> pipeline.stage2(digest, null), r -> {
            view.cost(r.usage(), 0);
            String removed = findings(r.personaRemoved());
            view.showOutput("Meta-prompt: compiled spec", r.spec().assembledPrompt() + removed + "\n\n" + json(r.spec()));
            stage3Dialog(r.spec(), r.personaRemoved().size()).showAndWait()
                    .ifPresent(payload -> runStage3(digest, r.spec(), payload));
        });
    }

    private void runStage3(PromptIntentDigest digest, CompiledPromptSpec spec, String samplePayload) {
        var request = MetaPromptPipeline.verificationRequest(digest, spec, samplePayload);
        background("Stage 3 of 3: verifying…", () -> pipeline.stage3(request, null), r -> {
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
            view.showOutput("Meta-prompt: deployment manifest", out.toString());
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
        view.activity(OperationController.Activity.WORKING);
        view.status(message);
        Thread.ofVirtual().name("yazi-metaprompt").start(() -> {
            try {
                T result = stage.run();
                Platform.runLater(() -> {
                    busy = false;
                    view.activity(OperationController.Activity.IDLE);
                    onDone.accept(result);
                });
            } catch (GatewayException e) {
                stopped(OperationController.describe(e));
            } catch (RuntimeException e) {
                stopped(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private void stopped(String reason) {
        Platform.runLater(() -> {
            busy = false;
            view.activity(OperationController.Activity.ERROR);
            view.status("Meta-prompt stopped: " + reason);
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

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.setResizable(true);
        Styles.dialog(alert, theme.get());
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
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
