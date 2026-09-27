package org.yazi.visual;

/** The two models the image tool offers (names and labels unchanged from VisualPromptApp). */
public enum GeminiModel {
    FLASH("gemini-3.6-flash", "Gemini 3.6 Flash (Fast Draft)"),
    PRO("gemini-3.1-pro", "Gemini 3.1 Pro (High-Fidelity Reasoning)");

    private final String endpointId;
    private final String label;

    GeminiModel(String endpointId, String label) {
        this.endpointId = endpointId;
        this.label = label;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getLabel() {
        return label;
    }
}
