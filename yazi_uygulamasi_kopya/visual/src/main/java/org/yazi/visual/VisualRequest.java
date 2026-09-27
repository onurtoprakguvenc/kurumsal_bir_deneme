package org.yazi.visual;

import java.util.Objects;

/**
 * Every setting of one image-prompt compilation. The old {@code VisualPromptApp} kept the active model, engine and
 * regional-pass switch in mutable fields shared by all web requests; here they travel with the request, so
 * concurrent compilations cannot see each other's settings.
 *
 * <p>Defaults match the old web endpoint: THREE_STAGE, Midjourney, FLASH, 16:9, "--style raw --v 6.1", regional
 * passes on.</p>
 *
 * @param rawScene     scene text for DIRECT and NARRATIVE modes
 * @param slotOne      THREE_STAGE: environment / spatial
 * @param slotTwo      THREE_STAGE: dramatic action (mandatory in that mode)
 * @param slotThree    THREE_STAGE: optional directives (lens, elevation, aspect ratio, engine flags)
 * @param flags        Midjourney flags without {@code --ar}
 */
public record VisualRequest(
        IngestionMode mode,
        String rawScene,
        String slotOne,
        String slotTwo,
        String slotThree,
        PromptCompiler.EngineProfile engine,
        GeminiModel model,
        String aspectRatio,
        String flags,
        boolean regionalPasses) {

    public static final String DEFAULT_ASPECT_RATIO = "16:9";
    public static final String DEFAULT_FLAGS = "--style raw --v 6.1";

    public VisualRequest {
        mode = Objects.requireNonNullElse(mode, IngestionMode.THREE_STAGE);
        rawScene = Objects.requireNonNullElse(rawScene, "");
        slotOne = Objects.requireNonNullElse(slotOne, "");
        slotTwo = Objects.requireNonNullElse(slotTwo, "");
        slotThree = Objects.requireNonNullElse(slotThree, "");
        engine = Objects.requireNonNullElse(engine, PromptCompiler.EngineProfile.MIDJOURNEY_V6);
        model = Objects.requireNonNullElse(model, GeminiModel.FLASH);
        aspectRatio = (aspectRatio == null || aspectRatio.isBlank()) ? DEFAULT_ASPECT_RATIO : aspectRatio.strip();
        flags = (flags == null) ? DEFAULT_FLAGS : flags;
    }

    /** DIRECT or NARRATIVE compilation of one scene text with default settings. */
    public static VisualRequest ofScene(IngestionMode mode, String scene) {
        return new VisualRequest(mode, scene, "", "", "", null, null, null, null, true);
    }
}
