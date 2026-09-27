package org.yazi.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ModelGateway;
import org.yazi.gateway.ScriptedGateway;
import org.yazi.gateway.StreamResult;
import org.yazi.gateway.Structured;
import org.yazi.gateway.TokenSink;
import org.yazi.metaprompt.MetaPromptContracts.CompiledPromptSpec;
import org.yazi.metaprompt.MetaPromptContracts.PromptIntentDigest;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationReport;
import org.yazi.metaprompt.MetaPromptContracts.StructuralPayload;
import org.yazi.metaprompt.MetaPromptPipeline;
import org.yazi.metaprompt.PatchMerger;
import org.yazi.metaprompt.PersonaLinter;
import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.pipeline.PipelineResult;
import org.yazi.motion.pipeline.PromptPipelineOrchestrator;
import org.yazi.visual.IngestionMode;
import org.yazi.visual.VisualPipeline;
import org.yazi.visual.VisualRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Compile menu features end to end, through the same helpers the dialogs use (input building and
 * side-panel formatting), without clicking through the dialogs.
 */
class CompileFeaturesEndToEndTest {

    private final ObjectMapper json = new ObjectMapper();

    // --- Video: local engine, realistic selections ----------------------------------------------

    private static PipelineResult video(String selection) {
        return new PromptPipelineOrchestrator().run(CompileActions.videoInput(
                selection, 10, AspectRatio.RATIO_16_9, null, ImperfectionLevel.OFF, ""));
    }

    @Test
    void videoFromEnglishSceneSelection() {
        PipelineResult r = video("A woman walks along a rainy street at night. "
                + "The camera slowly pushes in on her face as neon signs flicker behind her.");
        String panel = CompileActions.formatVideo(r);
        System.out.println("[video/en] " + r.finalState() + "\n" + panel.lines().limit(8).reduce("", (a, b) -> a + b + "\n"));

        assertInstanceOf(PipelineResult.Success.class, r);
        assertTrue(panel.startsWith("POSITIVE\n"));
        assertTrue(panel.contains("\nNEGATIVE\n") && panel.contains("\nTIMELINE\n") && panel.contains("PROMPT PACKAGE (JSON)"));
    }

    @Test
    void videoFromTurkishSceneSelection() {
        PipelineResult r = video("Yağmurlu bir gecede kadın ıslak sokakta yürüyor. "
                + "Kamera yavaşça yüzüne yaklaşıyor, arkasında neon tabelalar titriyor.");
        System.out.println("[video/tr] " + r.finalState() + "\n" + CompileActions.formatVideo(r).lines().limit(4).reduce("", (a, b) -> a + b + "\n"));
        assertInstanceOf(PipelineResult.Success.class, r);
    }

    @Test
    void videoFromNonSceneTextReportsTheEnginesOwnVerdict() {
        // The engine is verbatim: whatever its domain filter decides is shown, never an exception or a crash.
        PipelineResult r = video("Dear team, please write me a summary of the release notes by Friday.");
        String panel = CompileActions.formatVideo(r);
        System.out.println("[video/non-scene] " + r.finalState() + ": " + panel.lines().findFirst().orElse(""));
        assertFalse(panel.isBlank());
        if (r instanceof PipelineResult.Failure f) {
            assertTrue(panel.startsWith(f.errorCode() + ": "));
        }
    }

    // --- Image: pipeline + panel formatting --------------------------------------------------------

    @Test
    void imageFromSelectionIsFormattedForThePanel() throws Exception {
        ScriptedGateway gateway = new ScriptedGateway().reply("""
                {"dramaticAction":"A lighthouse keeper climbs the spiral stairs",
                 "cameraRig":{"viewportAngle":"eye level","focalLength":"35mm","primarySubjectOffset":"RIGHT_THIRD","cameraDistance":"medium shot"},
                 "environmentalOptics":{"primaryLightSource":"lantern glow","rimLight":"cold window rim","atmosphericParticulates":"sea mist","shutterSpeed":"1/125s"},
                 "promptClauses":{"subjectClause":"one lighthouse keeper in an oilskin coat","actionClause":"climbing, right hand on the iron rail",
                                  "spatialClause":"eye-level medium shot, subject on right third","surfaceClause":"lantern glow on wet iron, sea mist"}}""");
        VisualPipeline.VisualResult result = new VisualPipeline(gateway)
                .compile(VisualRequest.ofScene(IngestionMode.DIRECT, "The keeper climbs the stairs with a lantern."), null);
        String panel = CompileActions.formatImage(result);
        System.out.println("[image] " + panel.lines().limit(3).reduce("", (a, b) -> a + b + "\n"));

        assertTrue(panel.startsWith("PROMPT (Midjourney v6.1)\n"));
        assertTrue(panel.contains("--ar 16:9 --style raw --v 6.1"));
        assertTrue(panel.contains("one lighthouse keeper"));
    }

    // --- Meta-prompt: all three stages, digest edit, patch merge -----------------------------------

    @Test
    void metaPromptRunsAllThreeStagesWithJavaEnforcement() throws Exception {
        StageAwareGateway gateway = new StageAwareGateway(json);
        MetaPromptPipeline pipeline = new MetaPromptPipeline(gateway, null);

        PromptIntentDigest digest = pipeline.stage1("I want a prompt that turns support tickets into one-line summaries", null).digest();

        // The flow shows the digest as JSON and lets the writer edit it; simulate an edit round trip.
        String edited = json.writerWithDefaultPrettyPrinter().writeValueAsString(digest)
                .replace("Summarise support tickets", "Summarise support tickets in English");
        PromptIntentDigest reparsed = json.readValue(edited, PromptIntentDigest.class);
        assertEquals("Summarise support tickets in English", reparsed.targetObjective());

        MetaPromptPipeline.Stage2Result s2 = pipeline.stage2(reparsed, null);
        assertFalse(PersonaLinter.hasPersona(s2.spec().assembledPrompt()));
        assertEquals(1, s2.personaRemoved().size());

        MetaPromptPipeline.Stage3Result s3 = pipeline.stage3(
                MetaPromptPipeline.verificationRequest(reparsed, s2.spec(), ""), null);
        PatchMerger.Merged merged = PatchMerger.apply(s2.spec(), s3.report());
        String manifest = merged.report().deploymentManifest();
        System.out.println("[meta-prompt] final manifest:\n" + manifest);

        assertTrue(manifest.contains("## Hardened Operational Directives\n1. Reject tickets without an id."));
        assertTrue(manifest.contains("<dynamic_input>\nticket text: {{TICKET_TEXT}}\nticket-id: {{TICKET_ID}}\n</dynamic_input>"));
        assertFalse(PersonaLinter.hasPersona(manifest));
        assertEquals(1.0, merged.report().structuralIntegrityScore());
        assertEquals(3, gateway.calls, "exactly one stateless call per stage");
    }

    /** Answers each stage by recognising its (verbatim) system instruction. */
    private static final class StageAwareGateway implements ModelGateway {
        private final ObjectMapper json;
        int calls;

        StageAwareGateway(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode schema,
                                                      CancellationToken c) throws GatewayException {
            calls++;
            Object value;
            if (type == PromptIntentDigest.class) {
                value = new PromptIntentDigest("Summarise support tickets", List.of("one line per ticket"),
                        List.of("no speculation"), new StructuralPayload(List.of("ticket text", "ticket-id"), List.of("text line")),
                        List.of("empty ticket"));
            } else if (type == CompiledPromptSpec.class) {
                value = new CompiledPromptSpec("Execute: summarise each ticket in one line.", "No speculation.",
                        "ticket text, ticket-id", "one line", "Empty ticket: output EMPTY.",
                        "You are a helpful support analyst. Summarise each ticket in one line.");
            } else {
                value = new PromptVerificationReport(1.4, "empty ticket", "EMPTY", List.of("long inputs"),
                        List.of("Reject tickets without an id."),
                        "<system_instruction>\nAct as a triage bot. Summarise each ticket.\n</system_instruction>\n"
                                + "<payload_contract>x</payload_contract>\n<dynamic_input>\n{{ticket text}}\n</dynamic_input>");
            }
            return new Structured<>(type.cast(json.convertValue(value, type)), org.yazi.gateway.Usage.EMPTY);
        }
    }
}
