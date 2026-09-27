package org.yazi.metaprompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yazi.gateway.ScriptedGateway;
import org.yazi.metaprompt.MetaPromptContracts.CompiledPromptSpec;
import org.yazi.metaprompt.MetaPromptContracts.PromptIntentDigest;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationReport;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationRequest;
import org.yazi.metaprompt.MetaPromptContracts.StructuralPayload;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetaPromptPipelineTest {

    private final ScriptedGateway gateway = new ScriptedGateway();
    private final MetaPromptPipeline pipeline = new MetaPromptPipeline(gateway, null);
    private final ObjectMapper json = new ObjectMapper();

    private static final PromptIntentDigest DIGEST = new PromptIntentDigest("Summarise support tickets",
            List.of("one line per ticket"), List.of("no speculation"),
            new StructuralPayload(List.of("source text", "user-query", "schema.def"), List.of("JSON array")),
            List.of("empty input"));

    // --- unchanged calls ------------------------------------------------------------------------------

    @Test
    void stage1SendsOriginalPromptSchemaAndSettings() throws Exception {
        gateway.reply(json.writeValueAsString(DIGEST));
        MetaPromptPipeline.Stage1Result r = pipeline.stage1("summarise tickets please", null);

        ScriptedGateway.Recorded call = gateway.recorded().get(0);
        assertEquals(MetaPromptContracts.stage1System(), call.call().system());
        assertEquals("summarise tickets please", call.call().user());
        assertEquals(0.1, call.call().temperature());
        assertEquals(8192, call.call().maxOutputTokens());
        assertEquals(MetaPromptPipeline.DEFAULT_MODEL, call.call().model());
        assertNull(call.call().thinkingBudget());
        assertEquals(json.readTree(MetaPromptContracts.stage1Schema()), call.schema());
        assertEquals(DIGEST, r.digest());
    }

    @Test
    void stage2And3UseOriginalMessageWording() throws Exception {
        gateway.reply(json.writeValueAsString(new CompiledPromptSpec("Execute X.", "c", "in", "out", "e", "Execute X.")));
        pipeline.stage2(DIGEST, null);
        assertTrue(gateway.lastCall().user().startsWith(
                "Compile the following PromptIntentDigest into a production prompt specification:\n\n{\"targetObjective\""));
        assertEquals(MetaPromptContracts.stage2System(), gateway.lastCall().system());

        gateway.reply(json.writeValueAsString(new PromptVerificationReport(0.9, "t", "d", List.of(), List.of(), "<system_instruction>Execute X.</system_instruction>")));
        pipeline.stage3(new PromptVerificationRequest("Execute X.", "in", "", List.of()), null);
        assertTrue(gateway.lastCall().user().startsWith(
                "Verify and harden the following compiled prompt specification:\n\n{\"compiledPrompt\""));
        assertEquals(MetaPromptContracts.stage3System(), gateway.lastCall().system());
    }

    @Test
    void validationMessagesMatchTheOriginal() {
        assertEquals("Field 'rawConcept' is required and must be non-empty.",
                assertThrows(IllegalArgumentException.class, () -> pipeline.stage1(" ", null)).getMessage());
        assertEquals("Invalid PromptIntentDigest: targetObjective is required.",
                assertThrows(IllegalArgumentException.class, () -> pipeline.stage2(
                        new PromptIntentDigest("", null, null, null, null), null)).getMessage());
        assertEquals("Field 'inputSchema' is required and must be non-empty.",
                assertThrows(IllegalArgumentException.class, () -> pipeline.stage3(
                        new PromptVerificationRequest("p", " ", null, null), null)).getMessage());
        assertTrue(gateway.recorded().isEmpty());
    }

    @Test
    void verificationRequestIsBuiltLikeTheWebUi() {
        CompiledPromptSpec spec = new CompiledPromptSpec("d", "c", "in-schema", "o", "e", "assembled");
        PromptVerificationRequest req = MetaPromptPipeline.verificationRequest(DIGEST, spec, "  sample  ");
        assertEquals(new PromptVerificationRequest("assembled", "in-schema", "sample",
                List.of("source text", "user-query", "schema.def")), req);
    }

    // --- Java enforcement -----------------------------------------------------------------------------

    @Test
    void stage2RemovesPersonaSentences() throws Exception {
        gateway.reply(json.writeValueAsString(new CompiledPromptSpec(
                "You are an expert summariser. Parse each ticket and emit one line.",
                "Act as a strict reviewer.\nReject tickets without an id.",
                "in", "out", "If you are a new user, ask for an id.",
                "Assume the role of a support lead. Parse each ticket.")));
        MetaPromptPipeline.Stage2Result r = pipeline.stage2(DIGEST, null);

        assertEquals("Parse each ticket and emit one line.", r.spec().systemDirective());
        assertEquals("Reject tickets without an id.", r.spec().behavioralConstraints());
        assertEquals("If you are a new user, ask for an id.", r.spec().edgeCaseHandling(), "mid-sentence use is not persona framing");
        assertEquals("Parse each ticket.", r.spec().assembledPrompt());
        assertEquals(3, r.personaRemoved().size());
    }

    @Test
    void stage3EnforcesPlaceholdersPersonaAndScore() throws Exception {
        String manifest = """
                <system_instruction>
                Act as a senior engineer. Execute the parse. Never use "Act as" framing.
                </system_instruction>
                <payload_contract>schema</payload_contract>
                <dynamic_input>
                source: {{source text}}
                </dynamic_input>""";
        gateway.reply(json.writeValueAsString(new PromptVerificationReport(1.7, "t", "d", List.of("risk"),
                List.of("You are a validator. Reject empty input.", "Enforce JSON output."), manifest)));

        MetaPromptPipeline.Stage3Result r = pipeline.stage3(
                new PromptVerificationRequest("p", "in", "", List.of("source text", "user-query", "schema.def")), null);
        PromptVerificationReport report = r.report();

        assertEquals(1.0, report.structuralIntegrityScore());
        assertEquals(List.of("Reject empty input.", "Enforce JSON output."), report.hardeningPatches());
        assertTrue(report.deploymentManifest().contains("<system_instruction>\nExecute the parse. Never use \"Act as\" framing.\n</system_instruction>"),
                report.deploymentManifest());
        assertTrue(report.deploymentManifest().contains(
                "<dynamic_input>\nsource text: {{SOURCE_TEXT}}\nuser-query: {{USER_QUERY}}\nschema.def: {{SCHEMADEF}}\n</dynamic_input>"),
                report.deploymentManifest());
        assertTrue(r.dynamicInputRebuilt());
        assertEquals(List.of("{{source text}}"), r.invalidTokens());
        assertFalse(PersonaLinter.hasPersona(report.deploymentManifest()));
    }

    @Test
    void placeholderRuleMatchesThePromptExamples() {
        assertEquals("{{SOURCE_TEXT}}", PlaceholderNormalizer.token("source text"));
        assertEquals("{{USER_QUERY}}", PlaceholderNormalizer.token("user-query"));
        assertEquals("{{SCHEMADEF}}", PlaceholderNormalizer.token("schema.def"));
        assertEquals("{{A_B}}", PlaceholderNormalizer.token("  __a  --  b__ "));
        assertEquals("", PlaceholderNormalizer.token("!!!"));
        assertEquals("\ninput: {{INPUT}}\n", PlaceholderNormalizer.dynamicInputBody(List.of()));
        assertEquals("\ninput: {{INPUT}}\n", PlaceholderNormalizer.dynamicInputBody(null));
        assertEquals("\nsource text: {{SOURCE_TEXT}}\n",
                PlaceholderNormalizer.dynamicInputBody(List.of("source text", "Source-Text")), "duplicates collapse");
    }

    @Test
    void missingDynamicInputBlockIsAppended() {
        PlaceholderNormalizer.Enforced e = PlaceholderNormalizer.enforce("<system_instruction>x</system_instruction>", List.of("q"));
        assertTrue(e.manifest().endsWith("<dynamic_input>\nq: {{Q}}\n</dynamic_input>"));
        assertTrue(e.changed());
    }

    @Test
    void compliantManifestIsLeftUntouched() {
        String manifest = "<dynamic_input>\nq: {{Q}}\n</dynamic_input>";
        PlaceholderNormalizer.Enforced e = PlaceholderNormalizer.enforce(manifest, List.of("q"));
        assertEquals(manifest, e.manifest());
        assertFalse(e.changed());
    }

    @Test
    void personaLinterRemovesListItemsAndKeepsIndentation() {
        String text = "Rules:\n- You are a bot.\n- Do Y.\n    indented line stays";
        PersonaLinter.Result r = PersonaLinter.strip("f", text);
        assertEquals("Rules:\n- Do Y.\n    indented line stays", r.text());
        assertEquals("- You are a bot.", r.findings().get(0).removed());
    }

    @Test
    void personaLinterLeavesCleanTextUnchanged() {
        String text = "Execute the parse. Enforce the schema.";
        assertSame(text, PersonaLinter.strip("f", text).text());
    }

    // --- patch merge (port of the web UI's applyPatches) ---------------------------------------------

    @Test
    void patchesAreMergedLikeTheWebUi() {
        CompiledPromptSpec spec = new CompiledPromptSpec("d", "c", "i", "o", "e", "ASSEMBLED");
        PromptVerificationReport report = new PromptVerificationReport(0.8, "t", "s", List.of(),
                List.of("Reject empty input.", "Enforce JSON."),
                "<system_instruction>\nEXEC\n</system_instruction>\n<dynamic_input>x</dynamic_input>");
        PatchMerger.Merged m = PatchMerger.apply(spec, report);

        String block = "\n\n## Hardened Operational Directives\n1. Reject empty input.\n2. Enforce JSON.";
        assertEquals("ASSEMBLED" + block, m.spec().assembledPrompt());
        assertEquals("<system_instruction>\nEXEC\n\n" + block + "\n</system_instruction>\n<dynamic_input>x</dynamic_input>",
                m.report().deploymentManifest());
    }

    @Test
    void patchesAreAppendedWhenTagIsMissing() {
        PatchMerger.Merged m = PatchMerger.apply(new CompiledPromptSpec("d", "c", "i", "o", "e", "A"),
                new PromptVerificationReport(0.8, "t", "s", List.of(), List.of("P."), "MANIFEST"));
        assertEquals("MANIFEST\n\n\n## Hardened Operational Directives\n1. P.", m.report().deploymentManifest());
    }
}
