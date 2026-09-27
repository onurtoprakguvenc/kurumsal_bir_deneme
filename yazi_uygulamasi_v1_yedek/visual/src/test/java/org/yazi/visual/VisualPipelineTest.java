package org.yazi.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.FinishReason;
import org.yazi.gateway.GatewayException;
import org.yazi.gateway.ModelCall;
import org.yazi.gateway.ScriptedGateway;
import org.yazi.gateway.StreamResult;
import org.yazi.gateway.Structured;
import org.yazi.gateway.TokenSink;
import org.yazi.gateway.Usage;
import org.yazi.gateway.CancellationToken;
import org.yazi.gateway.ModelGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VisualPipelineTest {

    static final String CONTRACT_JSON = """
            {
              "dramaticAction": "Two fencers lock blades mid-lunge",
              "cameraRig": {"viewportAngle": "eye level", "focalLength": "50mm", "primarySubjectOffset": "LEFT_THIRD",
                            "cameraDistance": "medium-full shot"},
              "interactionDynamics": {"contactPointCoordinate": "center, blade height", "mutualTensionVector": "opposing forward force",
                                      "anatomicalCommitment": "planted rear feet, extended sword arms"},
              "regionalPasses": [{"targetZone": "CONTACT_INTERACTION_ZONE", "boundingDescription": "center crossing blades",
                                  "isolatedPrompt": "two crossed foils, tips bent under load"}],
              "environmentalOptics": {"primaryLightSource": "overhead hall lights", "rimLight": "cool rim",
                                      "atmosphericParticulates": "clean indoor air", "shutterSpeed": "1/2000s"},
              "promptClauses": {"subjectClause": "two fencers in white", "actionClause": "blades locked in a lunge",
                                "spatialClause": "eye-level medium-full shot, subject on left third",
                                "surfaceClause": "matte canvas jackets, overhead light"}
            }""";

    private final ScriptedGateway gateway = new ScriptedGateway().reply(CONTRACT_JSON);
    private final VisualPipeline pipeline = new VisualPipeline(gateway);

    private static VisualRequest request(IngestionMode mode, String scene, GeminiModel model, boolean regional) {
        return new VisualRequest(mode, scene, "", "", "", null, model, null, null, regional);
    }

    // --- bug 1: the requested model is used -------------------------------------------------------

    @Test
    void requestedModelIsUsedForEveryCall() throws Exception {
        NarrativeAwareGateway g = new NarrativeAwareGateway();
        new VisualPipeline(g).compile(request(IngestionMode.NARRATIVE, "She ran, then fell, then rose.",
                GeminiModel.PRO, true), null);

        assertEquals(2, g.calls.size(), "keyframe + contract");
        for (ModelCall call : g.calls) {
            assertEquals("gemini-3.1-pro", call.model());
        }
    }

    @Test
    void defaultModelIsFlash() throws Exception {
        pipeline.compile(VisualRequest.ofScene(IngestionMode.DIRECT, "A lighthouse at dusk."), null);
        assertEquals("gemini-3.6-flash", gateway.lastCall().model());
    }

    // --- bug 2: no shared state between concurrent requests ---------------------------------------

    @Test
    void regionalPassSwitchIsPerRequestUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean regional = i % 2 == 0;
            String scene = "scene-" + i + " regional=" + regional;
            futures.add(pool.submit(() -> {
                go.await();
                return pipeline.compile(request(IngestionMode.DIRECT, scene, GeminiModel.FLASH, regional), null);
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<ScriptedGateway.Recorded> recorded = gateway.recorded();
        assertEquals(200, recorded.size());
        for (ScriptedGateway.Recorded r : recorded) {
            boolean asked = r.call().user().endsWith("regional=true");
            boolean inSchema = r.schema().path("properties").has("regionalPasses");
            assertEquals(asked, inSchema, "schema did not follow its own request: " + r.call().user());
        }
    }

    // --- unchanged behaviour ----------------------------------------------------------------------

    @Test
    void contractCallKeepsOriginalWordingAndSettings() throws Exception {
        pipeline.compile(VisualRequest.ofScene(IngestionMode.DIRECT, "A lighthouse at dusk."), null);
        ScriptedGateway.Recorded r = gateway.recorded().get(0);

        assertEquals("Extract grounded physical and visual parameters conforming to the response schema. "
                + "Capture explicit subject archetypes, visible attire/armor, active energy/powers, "
                + "and kinetic vectors. Do not strip character visual traits or fantasy effects. "
                + "Omit optional fields entirely when absent rather than filling them with placeholders.", r.call().system());
        assertEquals("Raw scene draft to structure:\nA lighthouse at dusk.", r.call().user());
        assertEquals(0.2, r.call().temperature());
        assertEquals(4000, r.call().maxOutputTokens());
        assertNull(r.call().thinkingBudget());
        assertEquals(SceneContract.buildGeminiResponseSchema(new ObjectMapper(), true), r.schema());
    }

    @Test
    void threeStageUsesSlotThreeOverridesWithoutAnExtraCall() throws Exception {
        VisualRequest req = new VisualRequest(IngestionMode.THREE_STAGE, "", "Old fencing hall, dusty light",
                "Two fencers lock blades", "35mm lens\n--ar 9:16 --chaos 20", null, null, null, null, true);
        VisualPipeline.VisualResult result = pipeline.compile(req, null);

        assertEquals(1, gateway.calls().size(), "three-stage assembly is local");
        assertTrue(gateway.lastCall().user().contains("Two fencers lock blades. 35mm lens. Setting: Old fencing hall, dusty light."));
        assertTrue(result.output().flags().startsWith("--ar 9:16 --chaos 20"), result.output().flags());
        assertTrue(result.logs().contains("Slot 3 aspect ratio override: --ar 9:16"));
    }

    @Test
    void threeStageRequiresSlotTwo() {
        VisualRequest req = new VisualRequest(IngestionMode.THREE_STAGE, "", "hall", " ", "", null, null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> pipeline.compile(req, null));
        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    void inlineAspectRatioOverridesAndIsStripped() throws Exception {
        VisualPipeline.VisualResult result = pipeline.compile(
                VisualRequest.ofScene(IngestionMode.DIRECT, "A lighthouse at dusk --ar 4:5"), null);
        assertEquals("Raw scene draft to structure:\nA lighthouse at dusk", gateway.lastCall().user());
        assertTrue(result.output().flags().contains("--ar 4:5"));
    }

    @Test
    void fluxOutputCarriesNoFlagsAndRegionalManifestIsBuilt() throws Exception {
        VisualRequest req = new VisualRequest(IngestionMode.DIRECT, "Two fencers.", "", "", "",
                PromptCompiler.EngineProfile.FLUX_1_DEV, null, null, null, true);
        VisualPipeline.VisualResult result = pipeline.compile(req, null);
        assertEquals("", result.output().flags());
        assertNotNull(result.regionalManifest());
        assertFalse(result.output().positivePrompt().isBlank());
    }

    @Test
    void truncatedContractIsReportedAsTruncated() {
        gateway.finishWith(FinishReason.MAX_TOKENS);
        GatewayException e = assertThrows(GatewayException.class,
                () -> pipeline.compile(VisualRequest.ofScene(IngestionMode.DIRECT, "x"), null));
        assertEquals(GatewayException.Kind.TRUNCATED, e.kind());
    }

    /** Answers the keyframe (streamed) call with prose and the contract (structured) call with JSON. */
    private static final class NarrativeAwareGateway implements ModelGateway {
        final ConcurrentLinkedQueue<ModelCall> calls = new ConcurrentLinkedQueue<>();
        private final ScriptedGateway structured = new ScriptedGateway().reply(CONTRACT_JSON);

        @Override
        public StreamResult stream(ModelCall call, TokenSink sink, CancellationToken c) {
            calls.add(call);
            return new StreamResult("She rises, palm flat on the floor.", FinishReason.STOP, new Usage(50, 12, 0, 0, 62));
        }

        @Override
        public <T> Structured<T> structuredWithSchema(ModelCall call, Class<T> type, JsonNode schema,
                                                      CancellationToken c) throws GatewayException {
            calls.add(call);
            return structured.structuredWithSchema(call, type, schema, c);
        }
    }
}
