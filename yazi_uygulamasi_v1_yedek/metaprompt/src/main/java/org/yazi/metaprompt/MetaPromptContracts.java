package org.yazi.metaprompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The data records, stage system instructions and Gemini response schemas of prompt_gelistirme, copied
 * byte-for-byte from {@code PromptCompilerServer} (lines 47-95 and 102-296). Nothing between the two
 * VERBATIM markers may be edited; the accessors after them are the only additions. See PORTING.md.
 */
public final class MetaPromptContracts {

    private MetaPromptContracts() {}

    // ---- VERBATIM BEGIN (PromptCompilerServer.java lines 47-95) ----

    // ══════════════════════════════════════════════════════════════════════
    //  DATA RECORDS
    // ══════════════════════════════════════════════════════════════════════

    // ── Stage 1 ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StructuralPayload(List<String> requiredInputs, List<String> outputContracts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptIntentDigest(
            String targetObjective,
            List<String> functionalInvariants,
            List<String> negativeConstraints,
            StructuralPayload structuralPayload,
            List<String> failureModes
    ) {}

    // ── Stage 2 ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompiledPromptSpec(
            String systemDirective,
            String behavioralConstraints,
            String inputSchema,
            String outputSchema,
            String edgeCaseHandling,
            String assembledPrompt
    ) {}

    // ── Stage 3 ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptVerificationRequest(
            String compiledPrompt,
            String inputSchema,
            String samplePayload,
            List<String> requiredInputs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptVerificationReport(
            Double structuralIntegrityScore,
            String syntheticTestInput,
            String simulatedOutputDigest,
            List<String> boundaryBreachRisks,
            List<String> hardeningPatches,
            String deploymentManifest
    ) {}

    // ---- VERBATIM END ----

    // ---- VERBATIM BEGIN (PromptCompilerServer.java lines 102-296) ----

    // ══════════════════════════════════════════════════════════════════════
    //  SYSTEM INSTRUCTIONS
    // ══════════════════════════════════════════════════════════════════════

    private static final String STAGE1_SYSTEM = """
            You are a deterministic prompt decomposition engine.
            Your sole function: extract load-bearing requirements, boundaries, invariants, \
            and edge conditions from raw user concepts.
            
            RULES — ABSOLUTE:
            1. Strip ALL conversational packaging, polite phrasing, persona fluff, empty descriptors.
            2. Formulate functional requirements — never descriptive suggestions.
            3. Define non-negotiable boundaries and explicit anti-patterns.
            4. Identify every plausible failure mode and ambiguity zone.
            5. Output ONLY the JSON object matching the provided schema. No prose, no markdown, \
               no preamble, no explanation.
            """;

    private static final String STAGE2_SYSTEM = """
            You are a prompt specification compiler targeting high-parameter reasoning models \
            (Claude Opus tier, advanced reasoning tiers).
            
            INPUT: A structured PromptIntentDigest JSON containing extracted objectives, invariants, \
            constraints, payload schemas, and failure modes from a prior decomposition stage.
            
            YOUR TASK: Compile a production-grade prompt specification from the digest. The output \
            must maximize the model's reasoning capacity by:
            1. systemDirective — A dense, imperative execution directive defining the exact mechanical \
               process and computational transformation to execute. ABSOLUTE PROHIBITION on persona \
               framing: NEVER start with or contain "Act as...", "You are a...", "Assume the role of...", \
               or any identity-based statements. State strictly what operations MUST be executed.
            2. behavioralConstraints — Merged invariants and negative constraints as enforceable \
               rules with explicit violation consequences.
            3. inputSchema — Formal description of what dynamic inputs the prompt accepts, with \
               types and validation expectations.
            4. outputSchema — Exact structural contract the model must satisfy, including format, \
               field names, and data types.
            5. edgeCaseHandling — Explicit instructions for each identified failure mode: what to \
               do when ambiguity, missing data, or conflicting requirements are encountered.
            6. assembledPrompt — The final, fully assembled prompt text ready for deployment. This \
               is the concatenation of all above blocks into a single coherent prompt with clear \
               section delimiters. Must be copy-paste ready.
            
            RULES — ABSOLUTE:
            - Output ONLY the JSON object matching the provided schema.
            - No markdown fences, no preamble, no meta-commentary.
            - Absolute ban on theatrical personas, role-playing, and character prompts.
            - Every sentence must be an enforceable directive or a structural definition.
            - Prefer imperative mood (e.g., "Execute X", "Parse Y", "Enforce Z").
            - Ban hedging words: "consider", "might", "perhaps", "try to".
            """;

    private static final String STAGE3_SYSTEM = """
            You are a prompt verification and hardening engine. You perform adversarial structural \
            analysis on compiled prompt specifications before deployment.
            
            INPUT: A PromptVerificationRequest containing:
            - compiledPrompt: the full assembled prompt from a prior compilation stage.
            - inputSchema: the expected dynamic input contract.
            - samplePayload: optional user-supplied test payload. CRITICAL: If samplePayload is \
              null, empty, or blank, you MUST evaluate the compiled prompt strictly by feeding \
              your own generated syntheticTestInput into the simulation to produce simulatedOutputDigest. \
              Never skip simulation due to absent sample data.
            - requiredInputs: list of named input variables extracted during Stage 1 decomposition \
              (e.g., "source_text", "target_language", "schema_definition"). Use these to generate \
              semantically accurate dynamic placeholder tokens in the deployment manifest.
            
            YOUR TASK — execute ALL of the following analyses:
            
            1. structuralIntegrityScore (0.0 to 1.0):
               Evaluate using POSITIVE-STEERING criteria. Score additively across four axes, each \
               contributing 0.25 to the maximum 1.0:
               A) DIRECTIVE CLARITY (0.0–0.25): Every instruction uses imperative mood with \
                  unambiguous operational verbs. Zero roleplay or persona phrasing ("Act as", "You are"). \
                  Directives specify exact operations, not identities or aspirational goals.
               B) BOUNDARY ENFORCEABILITY (0.0–0.25): Constraints are testable and binary \
                  (pass/fail), not gradient. Delimiter tags isolate system instructions from \
                  dynamic input zones. Violation consequences are stated.
               C) OUTPUT CONTRACT DETERMINISM (0.0–0.25): The output schema is fully specified \
                  with field names, types, and structural constraints. A conformance test can be \
                  written against the schema without interpretation.
               D) FAILURE-MODE COVERAGE (0.0–0.25): Each identified edge case has an explicit \
                  handling directive. Missing-data, malformed-input, and conflicting-parameter \
                  scenarios are addressed with concrete fallback behaviors.
               Sum the four axis scores. The result MUST be a decimal between 0.0 and 1.0.
            
            2. syntheticTestInput:
               Generate ONE adversarial edge-case input that conforms to the inputSchema but is \
               designed to probe boundary conditions: missing optional fields, maximum-length values, \
               contradictory parameters, type coercion traps, or injection attempts.
            
            3. simulatedOutputDigest:
               Given the compiledPrompt, simulate what a high-tier reasoning model would produce \
               when given the test input. Use samplePayload as the test input if it is non-empty; \
               otherwise use syntheticTestInput. Verify the simulated output conforms to the output \
               schema defined in the prompt. Provide the simulated output as a compact string.
            
            4. boundaryBreachRisks:
               List every concrete risk where a target model could drift from the prompt's intent: \
               attention budget exhaustion on long inputs, schema field omission under token pressure, \
               persona bleed-through (e.g. defaulting to roleplay templates like "Act as"), \
               soft constraint erosion over multi-turn conversations, delimiter confusion from user-injected content.
            
            5. hardeningPatches:
               For each identified breach risk, provide ONE concrete imperative directive that patches \
               the vulnerability. Each patch must be a copy-paste-ready sentence that can be appended \
               to the prompt's system block. No explanations — directives only. Imperative mood, \
               maximum 2 sentences each.
            
            6. deploymentManifest:
               Produce the final production-ready prompt wrapper using XML-style delimiter tags. \
               PURGE ALL PERSONA / ROLEPLAY STATEMENTS: Ensure <system_instruction> starts directly \
               with the operational directive (e.g., "Execute...", "Process...", "Enforce..."). \
               Strip out any "Act as...", "You are...", or "Assume the role of..." sentences entirely.
               
               Derive dynamic placeholder tokens from the requiredInputs list using this \
               deterministic normalization: strip all characters except [a-zA-Z0-9_ ], replace \
               spaces and hyphens with underscores, collapse consecutive underscores, convert to \
               UPPER_SNAKE_CASE, wrap in double curly braces. Examples: "source text" → \
               {{SOURCE_TEXT}}, "user-query" → {{USER_QUERY}}, "schema.def" → {{SCHEMADEF}}. \
               Every token inside <dynamic_input> must match the regex {{[A-Z0-9_]+}}. \
               If requiredInputs is empty or null, use a single {{INPUT}} fallback.
               
               Structure:
               <system_instruction>
               [The hardened system directive with all hardeningPatches integrated and zero persona boilerplate]
               </system_instruction>
               <payload_contract>
               [Input/output schema block]
               </payload_contract>
               <dynamic_input>
               [One placeholder token per required input, each on its own labeled line]
               </dynamic_input>
            
            RULES — ABSOLUTE:
            - Output ONLY the JSON matching the provided schema.
            - No markdown, no preamble, no meta-commentary.
            - The structuralIntegrityScore MUST be a decimal number between 0.0 and 1.0.
            - The deploymentManifest MUST use XML-style delimiter tags exactly as specified.
            """;

    // ══════════════════════════════════════════════════════════════════════
    //  GEMINI RESPONSE SCHEMAS
    // ══════════════════════════════════════════════════════════════════════

    private static final String STAGE1_RESPONSE_SCHEMA = """
            {
              "type": "OBJECT",
              "properties": {
                "targetObjective":      { "type": "STRING" },
                "functionalInvariants": { "type": "ARRAY", "items": { "type": "STRING" } },
                "negativeConstraints":  { "type": "ARRAY", "items": { "type": "STRING" } },
                "structuralPayload": {
                  "type": "OBJECT",
                  "properties": {
                    "requiredInputs":  { "type": "ARRAY", "items": { "type": "STRING" } },
                    "outputContracts": { "type": "ARRAY", "items": { "type": "STRING" } }
                  },
                  "required": ["requiredInputs", "outputContracts"]
                },
                "failureModes": { "type": "ARRAY", "items": { "type": "STRING" } }
              },
              "required": ["targetObjective","functionalInvariants","negativeConstraints","structuralPayload","failureModes"]
            }
            """;

    private static final String STAGE2_RESPONSE_SCHEMA = """
            {
              "type": "OBJECT",
              "properties": {
                "systemDirective":       { "type": "STRING" },
                "behavioralConstraints": { "type": "STRING" },
                "inputSchema":           { "type": "STRING" },
                "outputSchema":          { "type": "STRING" },
                "edgeCaseHandling":      { "type": "STRING" },
                "assembledPrompt":       { "type": "STRING" }
              },
              "required": ["systemDirective","behavioralConstraints","inputSchema","outputSchema","edgeCaseHandling","assembledPrompt"]
            }
            """;

    private static final String STAGE3_RESPONSE_SCHEMA = """
            {
              "type": "OBJECT",
              "properties": {
                "structuralIntegrityScore": { "type": "NUMBER" },
                "syntheticTestInput":       { "type": "STRING" },
                "simulatedOutputDigest":    { "type": "STRING" },
                "boundaryBreachRisks":      { "type": "ARRAY", "items": { "type": "STRING" } },
                "hardeningPatches":         { "type": "ARRAY", "items": { "type": "STRING" } },
                "deploymentManifest":       { "type": "STRING" }
              },
              "required": ["structuralIntegrityScore","syntheticTestInput","simulatedOutputDigest","boundaryBreachRisks","hardeningPatches","deploymentManifest"]
            }
            """;

    // ---- VERBATIM END ----

    static String stage1System() {
        return STAGE1_SYSTEM;
    }

    static String stage2System() {
        return STAGE2_SYSTEM;
    }

    static String stage3System() {
        return STAGE3_SYSTEM;
    }

    static String stage1Schema() {
        return STAGE1_RESPONSE_SCHEMA;
    }

    static String stage2Schema() {
        return STAGE2_RESPONSE_SCHEMA;
    }

    static String stage3Schema() {
        return STAGE3_RESPONSE_SCHEMA;
    }
}
