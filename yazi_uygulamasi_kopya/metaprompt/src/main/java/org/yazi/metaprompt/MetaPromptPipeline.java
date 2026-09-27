package org.yazi.metaprompt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.Structured;
import org.yazi.gateway.Usage;
import org.yazi.metaprompt.MetaPromptContracts.CompiledPromptSpec;
import org.yazi.metaprompt.MetaPromptContracts.PromptIntentDigest;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationReport;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * prompt_gelistirme's three stages as pure functions: raw concept → {@link PromptIntentDigest} →
 * {@link CompiledPromptSpec} → {@link PromptVerificationReport}. Each stage is one stateless model call; the
 * intermediate records are values the caller holds (and may edit), never a conversation.
 *
 * <p>Unchanged from {@code PromptCompilerServer}: the records, system instructions and response schemas
 * ({@link MetaPromptContracts}, verbatim), the user-message wording, temperature 0.1, 8192 output tokens,
 * the input validation messages, the lenient JSON reading and the score clamp.</p>
 *
 * <p>Added: the deterministic rules the prompts ask for are enforced in Java after each stage:</p>
 * <ul>
 *   <li>Stage 2: persona sentences are removed from the directive, constraints, edge-case handling and the
 *       assembled prompt ({@link PersonaLinter}).</li>
 *   <li>Stage 3: persona sentences are removed from the manifest's {@code <system_instruction>} and from the
 *       hardening patches; the {@code <dynamic_input>} block is rebuilt from {@code requiredInputs} with the
 *       placeholder rule ({@link PlaceholderNormalizer}).</li>
 * </ul>
 */
public final class MetaPromptPipeline {

    /** The default of the original's {@code GEMINI_MODEL} setting. */
    public static final String DEFAULT_MODEL = "gemini-3.5-flash";

    static final int MAX_TOKENS = 8192;
    static final double TEMPERATURE = 0.1;

    /** Same serialisation as the original ({@code NON_NULL}), so stage inputs are sent exactly as before. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final ModelGateway gateway;
    private final String model;
    private final JsonNode stage1Schema;
    private final JsonNode stage2Schema;
    private final JsonNode stage3Schema;

    public MetaPromptPipeline(ModelGateway gateway, String model) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.strip();
        this.stage1Schema = parse(MetaPromptContracts.stage1Schema());
        this.stage2Schema = parse(MetaPromptContracts.stage2Schema());
        this.stage3Schema = parse(MetaPromptContracts.stage3Schema());
    }

    public record Stage1Result(PromptIntentDigest digest, Usage usage) {}

    public record Stage2Result(CompiledPromptSpec spec, List<PersonaLinter.Finding> personaRemoved, Usage usage) {}

    /**
     * @param dynamicInputRebuilt true when the model's {@code <dynamic_input>} block differed from the canonical one
     * @param invalidTokens       placeholder tokens the model wrote that break the {@code {{[A-Z0-9_]+}}} rule
     */
    public record Stage3Result(PromptVerificationReport report, List<PersonaLinter.Finding> personaRemoved,
                               boolean dynamicInputRebuilt, List<String> invalidTokens, Usage usage) {}

    // ------------------------------------------------------------------------------------------------
    // Stage 1: raw concept -> PromptIntentDigest
    // ------------------------------------------------------------------------------------------------

    public Stage1Result stage1(String rawConcept, CancellationToken cancellation) throws GatewayException {
        if (rawConcept == null || rawConcept.isBlank()) {
            throw new IllegalArgumentException("Field 'rawConcept' is required and must be non-empty.");
        }
        Structured<PromptIntentDigest> out = call(MetaPromptContracts.stage1System(), rawConcept,
                stage1Schema, PromptIntentDigest.class, cancellation);
        return new Stage1Result(out.value(), out.usage());
    }

    // ------------------------------------------------------------------------------------------------
    // Stage 2: PromptIntentDigest -> CompiledPromptSpec
    // ------------------------------------------------------------------------------------------------

    public Stage2Result stage2(PromptIntentDigest digest, CancellationToken cancellation) throws GatewayException {
        if (digest == null || digest.targetObjective() == null || digest.targetObjective().isBlank()) {
            throw new IllegalArgumentException("Invalid PromptIntentDigest: targetObjective is required.");
        }
        String userMessage = "Compile the following PromptIntentDigest into a production prompt specification:\n\n"
                + toJson(digest);
        Structured<CompiledPromptSpec> out = call(MetaPromptContracts.stage2System(), userMessage,
                stage2Schema, CompiledPromptSpec.class, cancellation);

        CompiledPromptSpec raw = out.value();
        List<PersonaLinter.Finding> removed = new ArrayList<>();
        CompiledPromptSpec linted = new CompiledPromptSpec(
                lint("systemDirective", raw.systemDirective(), removed),
                lint("behavioralConstraints", raw.behavioralConstraints(), removed),
                raw.inputSchema(),
                raw.outputSchema(),
                lint("edgeCaseHandling", raw.edgeCaseHandling(), removed),
                lint("assembledPrompt", raw.assembledPrompt(), removed));
        return new Stage2Result(linted, List.copyOf(removed), out.usage());
    }

    // ------------------------------------------------------------------------------------------------
    // Stage 3: PromptVerificationRequest -> PromptVerificationReport
    // ------------------------------------------------------------------------------------------------

    /** Builds the Stage 3 input the way the original web UI did. */
    public static PromptVerificationRequest verificationRequest(PromptIntentDigest digest, CompiledPromptSpec spec,
                                                                String samplePayload) {
        List<String> requiredInputs = (digest != null && digest.structuralPayload() != null
                && digest.structuralPayload().requiredInputs() != null)
                ? digest.structuralPayload().requiredInputs() : List.of();
        return new PromptVerificationRequest(
                spec.assembledPrompt() == null ? "" : spec.assembledPrompt(),
                spec.inputSchema() == null ? "" : spec.inputSchema(),
                samplePayload == null ? "" : samplePayload.strip(),
                requiredInputs);
    }

    public Stage3Result stage3(PromptVerificationRequest request, CancellationToken cancellation)
            throws GatewayException {
        if (request.compiledPrompt() == null || request.compiledPrompt().isBlank()) {
            throw new IllegalArgumentException("Field 'compiledPrompt' is required and must be non-empty.");
        }
        if (request.inputSchema() == null || request.inputSchema().isBlank()) {
            throw new IllegalArgumentException("Field 'inputSchema' is required and must be non-empty.");
        }
        String userMessage = "Verify and harden the following compiled prompt specification:\n\n" + toJson(request);
        Structured<PromptVerificationReport> out = call(MetaPromptContracts.stage3System(), userMessage,
                stage3Schema, PromptVerificationReport.class, cancellation);
        PromptVerificationReport report = out.value();

        // Clamp score to [0.0, 1.0] in case the model drifted (as in the original).
        Double score = report.structuralIntegrityScore();
        if (score != null) {
            score = Math.max(0.0, Math.min(1.0, score));
        }

        List<PersonaLinter.Finding> removed = new ArrayList<>();
        List<String> patches = new ArrayList<>();
        if (report.hardeningPatches() != null) {
            for (String patch : report.hardeningPatches()) {
                String cleaned = lint("hardeningPatches", patch, removed);
                if (!cleaned.isBlank()) {
                    patches.add(cleaned);
                }
            }
        }
        String manifest = lintSystemInstruction(report.deploymentManifest(), removed);
        PlaceholderNormalizer.Enforced enforced = PlaceholderNormalizer.enforce(manifest, request.requiredInputs());

        PromptVerificationReport checked = new PromptVerificationReport(score, report.syntheticTestInput(),
                report.simulatedOutputDigest(), report.boundaryBreachRisks(), List.copyOf(patches), enforced.manifest());
        return new Stage3Result(checked, List.copyOf(removed), enforced.changed(), enforced.invalidTokensFound(),
                out.usage());
    }

    // ------------------------------------------------------------------------------------------------

    private <T> Structured<T> call(String system, String user, JsonNode schema, Class<T> type,
                                   CancellationToken cancellation) throws GatewayException {
        ModelCall call = new ModelCall(model, system, user, TEMPERATURE, MAX_TOKENS, null, List.of());
        return gateway.structuredWithSchema(call, type, schema, cancellation);
    }

    private static String lint(String field, String text, List<PersonaLinter.Finding> sink) {
        PersonaLinter.Result result = PersonaLinter.strip(field, text);
        sink.addAll(result.findings());
        return result.text();
    }

    /** Lints only the text inside {@code <system_instruction>...</system_instruction>}. */
    private static String lintSystemInstruction(String manifest, List<PersonaLinter.Finding> sink) {
        if (manifest == null) {
            return "";
        }
        String open = "<system_instruction>";
        int start = manifest.indexOf(open);
        int end = manifest.indexOf(PatchMerger.CLOSING_TAG, start + 1);
        if (start < 0 || end < 0) {
            return manifest;
        }
        int bodyStart = start + open.length();
        String body = manifest.substring(bodyStart, end);
        PersonaLinter.Result result = PersonaLinter.strip("deploymentManifest.system_instruction", body);
        if (result.findings().isEmpty()) {
            return manifest;
        }
        sink.addAll(result.findings());
        return manifest.substring(0, bodyStart) + "\n" + result.text() + "\n" + manifest.substring(end);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private static JsonNode parse(String schema) {
        try {
            return JSON.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid built-in schema", e);
        }
    }
}
