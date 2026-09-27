package org.yazi.motion.engines;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.MovementSpeed;
import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStyleMatrix;
import org.yazi.motion.domain.exception.CompilationIncompleteException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Build step 08: model-agnostic text prompts and the immutable manifest. */
public final class PromptPackageCompiler implements IPromptPackageCompiler {

    public static final String ENGINE_VERSION = "1.0.0-production";
    public static final String SCHEMA_VERSION = "2026.1";

    private final Clock clock;

    public PromptPackageCompiler() {
        this(Clock.systemUTC());
    }

    public PromptPackageCompiler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public PromptPackage compile(String requestId, double targetDuration, AspectRatio aspectRatio, VisualStyleMatrix style,
                                 List<TemporalSegment> timeline, List<ReferenceAssetBinding> assets, PromptOptions options) {
        PromptOptions opts = options == null ? PromptOptions.none() : options;
        if (requestId == null || requestId.isBlank()) {
            throw new CompilationIncompleteException("Compilation failed: Missing request identifier.", Map.of("missing_field", "request_id"));
        }
        if (timeline == null || timeline.isEmpty()) {
            throw new CompilationIncompleteException("Compilation failed: Temporal timeline is unpopulated.", Map.of("missing_field", "temporal_segments"));
        }
        if (style == null) {
            throw new CompilationIncompleteException("Compilation failed: Style matrix is missing.", Map.of("missing_field", "style_matrix"));
        }
        if (aspectRatio == null) {
            throw new CompilationIncompleteException("Compilation failed: Aspect ratio is missing.", Map.of("missing_field", "aspect_ratio"));
        }
        List<ReferenceAssetBinding> bound = assets == null ? List.of() : assets;

        // Stage-2 contract: re-evaluate the temporal completeness invariant before output.
        TemporalContinuity.assertTemporalContinuity(timeline, targetDuration);

        SceneAnalysis.FocusBudget focusBudget = SceneAnalysis.focusBudget(opts.sceneDescription(), opts.suppressDetailAreas());
        String positive = synthesizePositivePrompt(style, timeline, bound, opts.imperfectionLevel(), opts.specificImperfections());
        String negative = synthesizeNegativePrompt(style, timeline, focusBudget.terms(), opts.imperfectionLevel());
        String breakdown = synthesizeTemporalBreakdown(timeline, bound);
        for (Map.Entry<String, String> block : Map.of("positive_prompt_summary", positive,
                "negative_prompt_summary", negative, "temporal_breakdown_text", breakdown).entrySet()) {
            if (VendorSyntax.containsVendorSyntax(block.getValue())) {
                throw new CompilationIncompleteException("Compiled block contains vendor-specific syntax.",
                        Map.of("block", block.getKey()));
            }
        }

        try {
            return new PromptPackage(requestId, SCHEMA_VERSION, targetDuration, aspectRatio, style, timeline, bound,
                    new PromptPackage.CompiledPrompts(positive, negative, breakdown),
                    new PromptPackage.Metadata(Instant.now(clock).toString(), ENGINE_VERSION));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new CompilationIncompleteException("PromptPackage assembly failed: " + ex.getMessage(), Map.of(), ex);
        }
    }

    static String synthesizePositivePrompt(VisualStyleMatrix style, List<TemporalSegment> timeline,
                                           List<ReferenceAssetBinding> assets) {
        return synthesizePositivePrompt(style, timeline, assets, ImperfectionLevel.OFF, "");
    }

    /** Imperfection phrases for LIGHT / PRONOUNCED, then the user's specific imperfections exactly as entered. */
    static String synthesizePositivePrompt(VisualStyleMatrix style, List<TemporalSegment> timeline,
                                           List<ReferenceAssetBinding> assets, ImperfectionLevel imperfection,
                                           String specificImperfections) {
        String story = timeline.stream().map(s -> baseDescription(s.description())).distinct().collect(Collectors.joining("; "));
        StringBuilder sb = new StringBuilder()
                .append("[Style: ").append(style.preset()).append("] ").append(story).append(". ")
                .append("Rendered with ").append(fmt(style.lensMm())).append("mm lens, ").append(style.lightingProfile()).append(". ")
                .append("Color palette: ").append(String.join(", ", style.colorPalette())).append(". ")
                .append("Atmospheric density: ").append(fmt(style.atmosphericDensity())).append(". ")
                .append("Target FPS: ").append(fmt(style.fps())).append(". ");
        if (!assets.isEmpty()) {
            sb.append("References: ").append(assets.stream()
                    .map(a -> a.assetId() + " (" + a.assetType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ", "
                            + a.spatialPosition().name().toLowerCase(Locale.ROOT) + ", weight " + fmt(a.weight()) + ")")
                    .collect(Collectors.joining(", "))).append(". ");
        }
        List<String> imperfectionPhrases = SceneAnalysis.IMPERFECTION_PHRASES.get(imperfection);
        if (imperfectionPhrases != null) {
            List<String> phrases = new ArrayList<>(imperfectionPhrases);
            String specific = specificImperfections == null ? "" : VendorSyntax.strip(specificImperfections);
            if (!specific.isEmpty()) phrases.add(specific);
            sb.append("Organic imperfection: ").append(String.join(", ", phrases)).append(". ");
        }
        return sb.append("Master visual fidelity, cinematic compositions, hyper-coherent temporal flow.").toString();
    }

    static String synthesizeNegativePrompt(VisualStyleMatrix style, List<TemporalSegment> timeline) {
        return synthesizeNegativePrompt(style, timeline, List.of(), ImperfectionLevel.OFF);
    }

    /** Focus-budget terms first (scene focus or user suppress areas), then the generic and preset-specific terms. */
    static String synthesizeNegativePrompt(VisualStyleMatrix style, List<TemporalSegment> timeline, List<String> focusTerms,
                                           ImperfectionLevel imperfection) {
        Set<String> terms = new LinkedHashSet<>(focusTerms == null ? List.of() : focusTerms);
        terms.addAll(List.of("text", "watermark", "logo", "low resolution",
                "motion blur distortion", "flickering visual artifacts", "temporal drift", "inconsistent lighting",
                "morphing hands", "extra limbs", "broken continuity", "overexposed frames", "frame stuttering"));
        switch (style.preset()) {
            case ANIME -> terms.addAll(List.of("photorealistic textures", "3D render look", "inconsistent line weight"));
            case HYPERREALISTIC_8K -> terms.addAll(List.of("cartoon look", "plastic CGI skin", "painterly textures"));
            case DOCUMENTARY -> terms.addAll(List.of("staged glossy look", "artificial color grading"));
            case NEO_NOIR -> terms.addAll(List.of("flat even lighting", "oversaturated colors"));
            case MVT_CYBERPUNK -> terms.addAll(List.of("daylight flat look", "muddy neon colors"));
            case CINEMATIC_35MM -> terms.addAll(List.of("video camera look", "oversharpened digital edges"));
        }
        if (timeline.stream().anyMatch(s -> s.kinematics().movementSpeed() == MovementSpeed.SLOW
                && s.pacingNote().startsWith("Slow motion"))) {
            terms.add("choppy slow motion");
        }
        // pronounced imperfection asks for organic flicker in the positive prompt; don't forbid it here
        if (imperfection == ImperfectionLevel.PRONOUNCED) terms.remove("flickering visual artifacts");
        return String.join(", ", terms);
    }

    /** "[0.00s -> 3.44s] WIDE_SHOT, EYE_LEVEL, PAN_RIGHT (SLOW) - description | Assets: id(w:0.95)" */
    static String synthesizeTemporalBreakdown(List<TemporalSegment> timeline, List<ReferenceAssetBinding> assets) {
        List<String> lines = new ArrayList<>();
        for (TemporalSegment seg : timeline) {
            String timeTag = String.format(Locale.ROOT, "[%.2fs -> %.2fs]", seg.startSecond(), seg.endSecond());
            String motion = seg.kinematics().shotType() + ", " + seg.kinematics().cameraAngle() + ", "
                    + seg.kinematics().movement() + " (" + seg.kinematics().movementSpeed() + ")";
            List<ReferenceAssetBinding> active = ReferenceAssetBinder.activeDuring(seg, assets);
            String assetTag = active.isEmpty() ? "" : " | Assets: " + active.stream()
                    .map(a -> a.assetId() + "(w:" + fmt(a.weight()) + ")").collect(Collectors.joining(", "));
            lines.add(timeTag + " " + motion + " - " + seg.description() + " | " + seg.pacingNote() + assetTag);
        }
        return String.join("\n", lines);
    }

    private static String baseDescription(String description) {
        String d = description.replaceAll("\\.\\s*Focus:.*$", "");
        String prev;
        do {
            prev = d;
            d = d.replaceAll("\\s*\\([^()]*\\)\\s*$", "");
        } while (!d.equals(prev));
        return d;
    }

    public static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
