package org.yazi.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.Structured;
import org.yazi.gateway.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scene text to compiled diffusion prompt: the compile path of image_generate_prompt_improve's
 * {@code VisualPromptApp.handleApiCompile}, without its HTTP server, CLI and shared mutable state.
 *
 * <p>Kept word-for-word: the contract system instruction, the user-turn wording, temperature 0.2, output
 * ceiling 4000, the hand-written {@link SceneContract} schema, the three ingestion modes, slot-3 and inline
 * {@code --ar} overrides, and the deterministic {@link PromptCompiler}.</p>
 *
 * <p>Fixed:</p>
 * <ul>
 *   <li>Shared state: the old app swapped {@code this.regionalPassesEnabled} on a shared instance served by four
 *       threads, so concurrent requests could get each other's schema. The schema is now built per request.</li>
 *   <li>Ignored model: the old contract call always read the {@code activeModel} field, not the model the request
 *       asked for. Every call now uses {@link VisualRequest#model()}.</li>
 * </ul>
 * <p>Gone with the server: reading a {@code .txt} path supplied by a request, and copying to the clipboard on the
 * server side (the editor has a Copy button).</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class VisualPipeline {

    static final int MAX_OUTPUT_TOKENS = 4000;
    static final double CONTRACT_TEMPERATURE = 0.2;

    static final String CONTRACT_SYSTEM_INSTRUCTION =
            "Extract grounded physical and visual parameters conforming to the response schema. "
                    + "Capture explicit subject archetypes, visible attire/armor, active energy/powers, "
                    + "and kinetic vectors. Do not strip character visual traits or fantasy effects. "
                    + "Omit optional fields entirely when absent rather than filling them with placeholders.";

    static final String CONTRACT_USER_PREFIX = "Raw scene draft to structure:\n";

    private static final Pattern INLINE_AR_PATTERN = Pattern.compile("--ar\\s+([0-9]+:[0-9]+)", Pattern.CASE_INSENSITIVE);

    /** Only builds schema nodes; never shared as mutable state. */
    private static final ObjectMapper NODES = new ObjectMapper();

    private final ModelGateway gateway;
    private final NarrativeExtractor narrativeExtractor;
    private final ThreeStageIngestor threeStageIngestor = new ThreeStageIngestor();

    public VisualPipeline(ModelGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.narrativeExtractor = new NarrativeExtractor(gateway);
    }

    /**
     * @param output           the compiled prompt (positive, negative, flags, regional passes)
     * @param structuralReport {@link PromptCompiler#generateStructuralReport}
     * @param regionalManifest null when there are no regional passes
     * @param logs             what the pipeline decided (overrides, keyframe), in order
     * @param usage            token usage summed over all model calls of this compilation
     */
    public record VisualResult(PromptCompiler.CompiledOutput output, SceneContract contract, String structuralReport,
                               String regionalManifest, List<String> logs, Usage usage) {
        public VisualResult {
            logs = List.copyOf(logs);
        }
    }

    public VisualResult compile(VisualRequest request, CancellationToken cancellation) throws GatewayException {
        List<String> reportLogs = new ArrayList<>();
        String modelEndpoint = request.model().getEndpointId();
        String effectiveAr = request.aspectRatio();
        String effectiveFlags = request.flags();
        Usage usage = Usage.EMPTY;
        String sceneText;

        if (request.mode() == IngestionMode.THREE_STAGE) {
            if (request.slotTwo().isBlank()) {
                throw new IllegalArgumentException("Slot 2 (dramatic action) is mandatory in three-stage mode.");
            }
            // modelEndpoint and apiKey are unused by the (deterministic) ingestor; the signature is kept verbatim.
            ThreeStageIngestor.StagedScene staged = threeStageIngestor.stage(
                    request.slotOne(), request.slotTwo(), request.slotThree(), modelEndpoint, null);
            reportLogs.addAll(staged.notes());
            ThreeStageIngestor.Directives directives = staged.directives();

            if (directives.hasAspectRatio()) {
                effectiveAr = directives.aspectRatio();
                reportLogs.add("Slot 3 aspect ratio override: --ar " + effectiveAr);
            }
            if (directives.hasEngineFlags()) {
                effectiveFlags = directives.engineFlagString();
                reportLogs.add("Slot 3 engine flags applied: " + effectiveFlags);
            }
            sceneText = staged.pipelineText();
        } else {
            sceneText = request.rawScene();
            if (sceneText.isBlank()) {
                throw new IllegalArgumentException("The scene text is empty.");
            }
            if (request.mode() == IngestionMode.NARRATIVE) {
                reportLogs.add("Resolving physical keyframe from narrative sequence...");
                NarrativeExtractor.Keyframe keyframe =
                        narrativeExtractor.extractKeyframe(sceneText, modelEndpoint, cancellation);
                sceneText = keyframe.text();
                usage = add(usage, keyframe.usage());
                reportLogs.add("Keyframe isolated: " + sceneText);
            }
        }

        Matcher arMatcher = INLINE_AR_PATTERN.matcher(sceneText);
        if (arMatcher.find()) {
            effectiveAr = arMatcher.group(1);
            sceneText = arMatcher.replaceAll("").trim();
            reportLogs.add("Inline aspect ratio override: --ar " + effectiveAr);
        }

        Structured<SceneContract> extracted = extractContract(sceneText, request, cancellation);
        SceneContract contract = extracted.value();
        usage = add(usage, extracted.usage());

        String structuralReport = PromptCompiler.generateStructuralReport(contract);
        String dynamicMjFlags = "--ar " + effectiveAr + " " + effectiveFlags;
        PromptCompiler.CompiledOutput output = PromptCompiler.compile(contract, request.engine(), dynamicMjFlags);
        String manifest = output.hasRegionalPrompts() ? PromptCompiler.compileRegionalManifest(output) : null;

        return new VisualResult(output, contract, structuralReport, manifest, reportLogs, usage);
    }

    private Structured<SceneContract> extractContract(String rawScene, VisualRequest request,
                                                      CancellationToken cancellation) throws GatewayException {
        // Built per call from the request's own flag: no shared field to race on.
        ObjectNode schema = SceneContract.buildGeminiResponseSchema(NODES, request.regionalPasses());
        ModelCall call = new ModelCall(request.model().getEndpointId(), CONTRACT_SYSTEM_INSTRUCTION,
                CONTRACT_USER_PREFIX + rawScene, CONTRACT_TEMPERATURE, MAX_OUTPUT_TOKENS, null, List.of());
        return gateway.structuredWithSchema(call, SceneContract.class, schema, cancellation);
    }

    private static Usage add(Usage a, Usage b) {
        return new Usage(a.promptTokens() + b.promptTokens(), a.outputTokens() + b.outputTokens(),
                a.thinkingTokens() + b.thinkingTokens(), a.cachedTokens() + b.cachedTokens(),
                a.totalTokens() + b.totalTokens());
    }
}
