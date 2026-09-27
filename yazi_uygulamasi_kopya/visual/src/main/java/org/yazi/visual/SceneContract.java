package org.yazi.visual;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Data transfer object and Gemini response-schema generator.
 *
 * <p>Key change from the previous revision: the model no longer emits two finished
 * prose paragraphs (one per engine). It emits four ordered clauses. {@link PromptCompiler}
 * owns token position, which is what actually determines whether a constraint lands
 * inside the encoder's effective attention window.
 *
 * <p>{@code propertyOrdering} forces the structural fields to be generated before the
 * clauses, so the clauses are conditioned on the extracted parameters rather than the
 * reverse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SceneContract(
        @JsonProperty(required = true, value = "dramaticAction")
        String dramaticAction,

        @JsonProperty(required = true, value = "cameraRig")
        CameraRig cameraRig,

        @JsonProperty(value = "subjectStance")
        SubjectStance subjectStance,

        @JsonProperty(value = "kineticAnchors")
        KineticAnchors kineticAnchors,

        @JsonProperty(value = "interactionDynamics")
        InteractionDynamics interactionDynamics,

        @JsonProperty(value = "surrealMountings")
        @JsonSetter(nulls = Nulls.AS_EMPTY)
        List<SurrealMounting> surrealMountings,

        @JsonProperty(value = "regionalPasses")
        @JsonSetter(nulls = Nulls.AS_EMPTY)
        List<RegionalPass> regionalPasses,

        @JsonProperty(required = true, value = "environmentalOptics")
        EnvironmentalOptics environmentalOptics,

        @JsonProperty(required = true, value = "promptClauses")
        PromptClauses promptClauses
) {

    public enum FrameOffset {
        @JsonProperty("LEFT_THIRD") LEFT_THIRD,
        @JsonProperty("RIGHT_THIRD") RIGHT_THIRD,
        @JsonProperty("CENTER_WEIGHTED") CENTER_WEIGHTED
    }

    public enum RegionalZone {
        @JsonProperty("PRIMARY_SUBJECT") PRIMARY_SUBJECT,
        @JsonProperty("SECONDARY_ACTOR") SECONDARY_ACTOR,
        @JsonProperty("CONTACT_INTERACTION_ZONE") CONTACT_INTERACTION_ZONE,
        @JsonProperty("ENVIRONMENT_BACKGROUND") ENVIRONMENT_BACKGROUND
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CameraRig(
            @JsonProperty(required = true, value = "viewportAngle") String viewportAngle,
            @JsonProperty(required = true, value = "focalLength") String focalLength,
            @JsonProperty(required = true, value = "primarySubjectOffset") FrameOffset primarySubjectOffset,
            @JsonProperty(required = true, value = "cameraDistance") String cameraDistance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubjectStance(
            @JsonProperty(required = true, value = "facingVector") String facingVector,
            @JsonProperty(required = true, value = "poseDynamics") String poseDynamics,
            @JsonProperty(required = true, value = "centerOfGravity") String centerOfGravity
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KineticAnchors(
            @JsonProperty(required = true, value = "exactOriginPoint") String exactOriginPoint,
            @JsonProperty(required = true, value = "forceTrajectory") String forceTrajectory,
            @JsonProperty(required = true, value = "physicalImpactArea") String physicalImpactArea
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InteractionDynamics(
            @JsonProperty(required = true, value = "contactPointCoordinate") String contactPointCoordinate,
            @JsonProperty(required = true, value = "mutualTensionVector") String mutualTensionVector,
            @JsonProperty(required = true, value = "anatomicalCommitment") String anatomicalCommitment
    ) {}

    /**
     * Decomposes a furniture/object noun into a geometric primitive and a mounting verb.
     * The original noun is captured for reporting only — {@link PromptCompiler} deliberately
     * omits it from the emitted prompt, since re-stating "bed frame on ceiling" is precisely
     * what triggers the bunk-bed heuristic this record exists to defeat.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SurrealMounting(
            @JsonProperty(required = true, value = "originalNoun") String originalNoun,
            @JsonProperty(required = true, value = "geometricPrimitive") String geometricPrimitive,
            @JsonProperty(required = true, value = "surfaceMountingVerb") String surfaceMountingVerb,
            @JsonProperty(required = true, value = "requiredNegatives") String requiredNegatives
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegionalPass(
            @JsonProperty(required = true, value = "targetZone") RegionalZone targetZone,
            @JsonProperty(required = true, value = "boundingDescription") String boundingDescription,
            @JsonProperty(required = true, value = "isolatedPrompt") String isolatedPrompt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnvironmentalOptics(
            @JsonProperty(required = true, value = "primaryLightSource") String primaryLightSource,
            @JsonProperty(required = true, value = "rimLight") String rimLight,
            @JsonProperty(required = true, value = "atmosphericParticulates") String atmosphericParticulates,
            @JsonProperty(required = true, value = "shutterSpeed") String shutterSpeed
    ) {}

    /**
     * Ordered prompt fragments. Phase 1 (geometry) is {@code subjectClause},
     * {@code actionClause}, {@code spatialClause}. Phase 2 (surface) is {@code surfaceClause}.
     * The compiler emits Phase 1 first so volumetric constraints occupy the head of the
     * attention window and material/optics degrade gracefully at the tail.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptClauses(
            @JsonProperty(required = true, value = "subjectClause") String subjectClause,
            @JsonProperty(required = true, value = "actionClause") String actionClause,
            @JsonProperty(required = true, value = "spatialClause") String spatialClause,
            @JsonProperty(required = true, value = "surfaceClause") String surfaceClause
    ) {}

    public static ObjectNode buildGeminiResponseSchema(ObjectMapper mapper) {
        return buildGeminiResponseSchema(mapper, true);
    }

    /**
     * @param includeRegionalPasses when false, the regionalPasses branch is omitted from the
     *                              schema entirely so no output tokens are spent generating
     *                              passes the caller has switched off.
     */
    public static ObjectNode buildGeminiResponseSchema(ObjectMapper mapper, boolean includeRegionalPasses) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        // 1. dramaticAction — fallback source if clause synthesis degrades.
        properties.putObject("dramaticAction")
                .put("type", "string")
                .put("description", "Grounded one-sentence summary of the primary action or environmental state.");

        // 2. cameraRig
        ObjectNode cameraNode = properties.putObject("cameraRig");
        cameraNode.put("type", "object");
        ObjectNode camProps = cameraNode.putObject("properties");
        camProps.putObject("viewportAngle")
                .put("type", "string")
                .put("description", "Standing spectator vantage, virtual camera altitude 1.6-1.8m. State the open clearance to the nearest figure directly, maintaining a clean upright eye-level perspective.");
        camProps.putObject("focalLength")
                .put("type", "string")
                .put("description", "Default 50mm normal prime unless the scene requires otherwise.");
        camProps.putObject("cameraDistance")
                .put("type", "string")
                .put("description", "e.g. medium-full shot. Must preserve the standing vantage and foreground clearance.");
        ArrayNode offsetEnum = camProps.putObject("primarySubjectOffset")
                .put("type", "string")
                .put("description", "Prefer LEFT_THIRD or RIGHT_THIRD. CENTER_WEIGHTED only for axial symmetry, one-point perspective, or a formal direct-stare portrait.")
                .putArray("enum");
        offsetEnum.add("LEFT_THIRD").add("RIGHT_THIRD").add("CENTER_WEIGHTED");
        cameraNode.putArray("required")
                .add("viewportAngle").add("focalLength").add("primarySubjectOffset").add("cameraDistance");

        // 3. subjectStance (optional)
        ObjectNode stanceNode = properties.putObject("subjectStance");
        stanceNode.put("type", "object");
        stanceNode.put("description", "Omit entirely for inanimate spaces, vehicles, architecture, or landscape. When present, the pose must resolve to a closed anatomical chain: hands and wrists attached to fully rendered forearms, shoulders, and torso, including in inverted poses.");
        ObjectNode stanceProps = stanceNode.putObject("properties");
        stanceProps.putObject("facingVector").put("type", "string")
                .put("description", "e.g. three-quarter profile facing left.");
        stanceProps.putObject("poseDynamics").put("type", "string")
                .put("description", "Full kinematic chain, continuous and intact. Never an isolated limb or torso fragment.");
        stanceProps.putObject("centerOfGravity").put("type", "string")
                .put("description", "e.g. weight on rear heel.");
        stanceNode.putArray("required").add("facingVector").add("poseDynamics").add("centerOfGravity");

        // 4. kineticAnchors (optional)
        ObjectNode kineticNode = properties.putObject("kineticAnchors");
        kineticNode.put("type", "object");
        kineticNode.put("description", "Omit entirely unless an active force, energy discharge, or projectile is present.");
        ObjectNode kineticProps = kineticNode.putObject("properties");
        kineticProps.putObject("exactOriginPoint").put("type", "string").put("description", "Point of force origin.");
        kineticProps.putObject("forceTrajectory").put("type", "string").put("description", "Directional vector of force.");
        kineticProps.putObject("physicalImpactArea").put("type", "string").put("description", "Zone of collision or displacement.");
        kineticNode.putArray("required").add("exactOriginPoint").add("forceTrajectory").add("physicalImpactArea");

        // 5. interactionDynamics (optional)
        ObjectNode interactionNode = properties.putObject("interactionDynamics");
        interactionNode.put("type", "object");
        interactionNode.put("description", "Contact mapping between two or more actively interacting subjects. Omit entirely for single-subject or non-contact scenes. Saturate the contact geometry with concrete displacement (skin indentation, fabric bunching, knuckle whitening) and keep primary subjects lexically separate from spectators.");
        ObjectNode interactionProps = interactionNode.putObject("properties");
        interactionProps.putObject("contactPointCoordinate").put("type", "string")
                .put("description", "Where contact occurs, e.g. 'upper right quadrant at neck level'.");
        interactionProps.putObject("mutualTensionVector").put("type", "string")
                .put("description", "Kinetic resistance between subjects, e.g. 'opposing inward lateral force'.");
        interactionProps.putObject("anatomicalCommitment").put("type", "string")
                .put("description", "Positive load-bearing structures only, designating active support points such as planted palms, continuous wrists, squared shoulders, and unbroken kinematic chains.");
        interactionNode.putArray("required")
                .add("contactPointCoordinate").add("mutualTensionVector").add("anatomicalCommitment");

        // 6. surrealMountings (optional)
        ObjectNode surrealNode = properties.putObject("surrealMountings");
        surrealNode.put("type", "array");
        surrealNode.put("description", "Omit or leave empty unless the scene genuinely contains non-standard physics or anti-gravity mounting. Decomposes object nouns into primitives and mounting verbs so the diffusion model cannot fall back on a stock furniture heuristic.");
        ObjectNode surrealItems = surrealNode.putObject("items");
        surrealItems.put("type", "object");
        ObjectNode surrealItemProps = surrealItems.putObject("properties");
        surrealItemProps.putObject("originalNoun").put("type", "string")
                .put("description", "The source noun, e.g. 'bed frame on ceiling'. Used for logging only.");
        surrealItemProps.putObject("geometricPrimitive").put("type", "string")
                .put("description", "Deconstructed geometry, e.g. 'flush horizontal mattress with zero clearance'.");
        surrealItemProps.putObject("surfaceMountingVerb").put("type", "string")
                .put("description", "Mounting phrase, e.g. 'suctioned flush against the plaster ceiling plane'.");
        surrealItemProps.putObject("requiredNegatives").put("type", "string")
                .put("description", "Comma-separated list of only the concrete components this mounting displaces, e.g. 'ladder, bunk bed, support posts, legs, stilts'. Never anatomical terms, never generic quality words.");
        surrealItems.putArray("required")
                .add("originalNoun").add("geometricPrimitive").add("surfaceMountingVerb").add("requiredNegatives");

        // 7. regionalPasses (optional, and omitted from the schema when disabled)
        if (includeRegionalPasses) {
            ObjectNode regionalArrayNode = properties.putObject("regionalPasses");
            regionalArrayNode.put("type", "array");
            regionalArrayNode.put("description", "Targeted inpainting passes. Omit or leave empty for single-subject or landscape-only scenes. When interactionDynamics is present, populate at least two distinct passes: one base anchor pass and one local contact-point pass.");
            ObjectNode regionalItems = regionalArrayNode.putObject("items");
            regionalItems.put("type", "object");
            ObjectNode regionalItemProps = regionalItems.putObject("properties");
            regionalItemProps.putObject("targetZone")
                    .put("type", "string")
                    .put("description", "Which staged element this pass targets.")
                    .putArray("enum")
                    .add("PRIMARY_SUBJECT").add("SECONDARY_ACTOR")
                    .add("CONTACT_INTERACTION_ZONE").add("ENVIRONMENT_BACKGROUND");
            regionalItemProps.putObject("boundingDescription").put("type", "string")
                    .put("description", "Spatial bounding hint, e.g. 'right third, neck and hand contact coordinates'.");
            regionalItemProps.putObject("isolatedPrompt").put("type", "string")
                    .put("description", "Local geometry and physical continuity within this zone only. Positive phrasing, no scene-wide context, no flags.");
            regionalItems.putArray("required")
                    .add("targetZone").add("boundingDescription").add("isolatedPrompt");
        }

        // 8. environmentalOptics
        ObjectNode opticsNode = properties.putObject("environmentalOptics");
        opticsNode.put("type", "object");
        ObjectNode opticsProps = opticsNode.putObject("properties");
        opticsProps.putObject("primaryLightSource").put("type", "string")
                .put("description", "e.g. soft diffuse morning window spill.");
        opticsProps.putObject("rimLight").put("type", "string")
                .put("description", "e.g. razor-sharp cool specular rim.");
        opticsProps.putObject("atmosphericParticulates").put("type", "string")
                .put("description", "e.g. floating dust motes in a sunbeam, clean indoor air.");
        opticsProps.putObject("shutterSpeed").put("type", "string")
                .put("description", "e.g. 1/2000s freeze-action shutter.");
        opticsNode.putArray("required")
                .add("primaryLightSource").add("rimLight").add("atmosphericParticulates").add("shutterSpeed");

        // 9. promptClauses — generated last, conditioned on everything above.
        ObjectNode clauseNode = properties.putObject("promptClauses");
        clauseNode.put("type", "object");
        clauseNode.put("description", "Four ordered prompt fragments assembled downstream. Each is a bare noun phrase with no leading article-of-instruction, no engine flags, no sentence connectives, and no quality boosters. Banned words: photorealistic, hyperrealistic, ultra-realistic, 8k, 16k, masterpiece, stunning, breathtaking, award winning, trending on artstation, unreal engine, octane render. State only what is physically present; never phrase anything as an exclusion.");
        ObjectNode clauseProps = clauseNode.putObject("properties");
        clauseProps.putObject("subjectClause").put("type", "string")
                .put("description", "Who or what is in frame, with the count and separation of actors made explicit so attributes cannot bleed between them.");
        clauseProps.putObject("actionClause").put("type", "string")
                .put("description", "The physical action and body geometry, stated as concrete displacement. Every extremity's position designated positively, including in inverted poses.");
        clauseProps.putObject("spatialClause").put("type", "string")
                .put("description", "Camera, framing, and depth planes, derived from cameraRig. Must match viewportAngle and primarySubjectOffset exactly.");
        clauseProps.putObject("surfaceClause").put("type", "string")
                .put("description", "Material, light, and atmosphere only, derived from environmentalOptics. No subject or camera terms here.");
        clauseNode.putArray("required")
                .add("subjectClause").add("actionClause").add("spatialClause").add("surfaceClause");

        // Root required
        schema.putArray("required")
                .add("dramaticAction")
                .add("cameraRig")
                .add("environmentalOptics")
                .add("promptClauses");

        // Generation order: structural extraction first, prose clauses last, so the
        // clauses are conditioned on the parameters instead of being back-filled.
        ArrayNode ordering = schema.putArray("propertyOrdering");
        ordering.add("dramaticAction")
                .add("cameraRig")
                .add("subjectStance")
                .add("kineticAnchors")
                .add("interactionDynamics")
                .add("surrealMountings")
                .add("environmentalOptics");
        if (includeRegionalPasses) {
            ordering.add("regionalPasses");
        }
        ordering.add("promptClauses");

        return schema;
    }
}