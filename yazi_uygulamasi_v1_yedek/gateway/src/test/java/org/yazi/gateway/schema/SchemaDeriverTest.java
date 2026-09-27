package org.yazi.gateway.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaDeriverTest {

    private final ObjectMapper json = new ObjectMapper();

    // Shapes taken from prompt_gelistirme's Stage 1 records.
    record StructuralPayload(List<String> requiredInputs, List<String> outputContracts) {}

    record PromptIntentDigest(
            String targetObjective,
            List<String> functionalInvariants,
            List<String> negativeConstraints,
            StructuralPayload structuralPayload,
            List<String> failureModes) {}

    @Test
    void reproducesHandWrittenStage1Schema() throws Exception {
        JsonNode expected = json.readTree("""
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
                      "propertyOrdering": ["requiredInputs", "outputContracts"],
                      "required": ["requiredInputs", "outputContracts"]
                    },
                    "failureModes": { "type": "ARRAY", "items": { "type": "STRING" } }
                  },
                  "propertyOrdering": ["targetObjective","functionalInvariants","negativeConstraints","structuralPayload","failureModes"],
                  "required": ["targetObjective","functionalInvariants","negativeConstraints","structuralPayload","failureModes"]
                }
                """);
        assertEquals(expected, SchemaDeriver.derive(json, PromptIntentDigest.class, SchemaOptions.none()));
    }

    // Shapes taken from image_generate_prompt_improve's SceneContract.
    enum FrameOffset {
        @JsonProperty("LEFT_THIRD") LEFT,
        RIGHT_THIRD
    }

    record CameraRig(@SchemaDescription("Default 50mm normal prime.") String focalLength, FrameOffset offset) {}

    record Scene(
            @JsonProperty("dramaticAction") String action,
            CameraRig cameraRig,
            @SchemaOptional List<String> regionalPasses,
            double shutterSpeed,
            int subjects,
            boolean interior) {}

    @Test
    void honoursJsonNamesEnumsDescriptionsAndOptionalFields() {
        JsonNode schema = SchemaDeriver.derive(json, Scene.class, null);

        assertTrue(schema.at("/properties/dramaticAction").isObject());
        assertTrue(schema.at("/properties/action").isMissingNode());
        assertEquals("[\"LEFT_THIRD\",\"RIGHT_THIRD\"]", schema.at("/properties/cameraRig/properties/offset/enum").toString());
        assertEquals("Default 50mm normal prime.",
                schema.at("/properties/cameraRig/properties/focalLength/description").asText());
        assertEquals("NUMBER", schema.at("/properties/shutterSpeed/type").asText());
        assertEquals("INTEGER", schema.at("/properties/subjects/type").asText());
        assertEquals("BOOLEAN", schema.at("/properties/interior/type").asText());
        assertFalse(schema.get("required").toString().contains("regionalPasses"));
        assertTrue(schema.get("propertyOrdering").toString().contains("regionalPasses"));
    }

    @Test
    void excludedPropertyLeavesSchemaEntirely() {
        JsonNode schema = SchemaDeriver.derive(json, Scene.class, SchemaOptions.excluding("regionalPasses"));
        assertTrue(schema.at("/properties/regionalPasses").isMissingNode());
        assertFalse(schema.get("propertyOrdering").toString().contains("regionalPasses"));
    }

    @Test
    void rejectsTyposInExclusions() {
        assertThrows(IllegalArgumentException.class,
                () -> SchemaDeriver.derive(json, Scene.class, SchemaOptions.excluding("regionalPass")));
    }

    record WithMap(Map<String, String> values) {}

    record Node(String name, List<Node> children) {}

    @Test
    void rejectsUnsupportedShapes() {
        assertThrows(IllegalArgumentException.class, () -> SchemaDeriver.derive(json, String.class, null));
        assertThrows(IllegalArgumentException.class, () -> SchemaDeriver.derive(json, WithMap.class, null));
        assertThrows(IllegalArgumentException.class, () -> SchemaDeriver.derive(json, Node.class, null));
    }
}
