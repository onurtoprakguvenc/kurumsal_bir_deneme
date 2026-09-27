package org.yazi.visual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Assembles engine-ready prompts from a {@link SceneContract}.
 *
 * <p>Assembly is slot-ordered rather than append-based. Geometry fragments occupy the head
 * of the string (highest effective attention weight, and the only region CLIP-L sees before
 * its 77-token truncation); surface and optics occupy the tail.
 *
 * <p>Negative constructions are rewritten affirmatively where an anatomical replacement is
 * mapped; unmapped constructions fall back to retaining the captured noun to prevent
 * structural clause truncation.
 */
public final class PromptCompiler {

    private PromptCompiler() {
    }

    public enum EngineProfile {
        MIDJOURNEY_V6("Midjourney v6.1"),
        FLUX_1_DEV("Flux.1-Dev");

        private final String displayName;

        EngineProfile(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** A single inpainting pass, normalized and ready to hand to a regional tool. */
    public record RegionalPrompt(String zone, String boundingDescription, String prompt) {
    }

    /**
     * Structured compiler result. {@code positivePrompt} never contains flags;
     * {@code flags} carries the fully merged Midjourney parameter string (empty for Flux);
     * {@code negativePrompt} carries the deduplicated negative list for engines that take
     * one out of band.
     */
    public record CompiledOutput(
            EngineProfile profile,
            String positivePrompt,
            String negativePrompt,
            String flags,
            List<RegionalPrompt> regionalPrompts
    ) {
        public String clipboardText() {
            return (flags == null || flags.isBlank()) ? positivePrompt : positivePrompt + " " + flags;
        }

        public boolean hasRegionalPrompts() {
            return regionalPrompts != null && !regionalPrompts.isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // Lexical policy
    // ---------------------------------------------------------------------

    private static final List<String> BANNED_TOKENS = List.of(
            "photorealistic", "hyperrealistic", "hyper-realistic", "ultra-realistic",
            "hyper-detailed", "ultra-detailed", "8k", "16k", "4k",
            "masterpiece", "trending on artstation", "trending", "stunning",
            "breathtaking", "unreal engine", "award winning", "award-winning", "octane render"
    );

    private static final List<Pattern> BANNED_PATTERNS = BANNED_TOKENS.stream()
            .map(token -> Pattern.compile("\\b" + Pattern.quote(token) + "\\b", Pattern.CASE_INSENSITIVE))
            .toList();

    /**
     * Concrete positive replacements for structural/anatomical artifacts.
     * Over-restrictive full-body and finger-count assertions removed.
     */
    private static final Map<Pattern, String> POSITIVE_SUBSTITUTIONS = new LinkedHashMap<>();

    static {
        POSITIVE_SUBSTITUTIONS.put(
                Pattern.compile("(?i)\\bfloating\\s+(hands?|limbs?|arms?)\\b"),
                "palms flattened against the contact surface, wrists continuous into forearms");
        POSITIVE_SUBSTITUTIONS.put(
                Pattern.compile("(?i)\\bhovering\\s+hands?\\b"),
                "fingertips depressing and sinking into skin");
        POSITIVE_SUBSTITUTIONS.put(
                Pattern.compile("(?i)\\bsuperficial\\s+hand\\s+placement\\b"),
                "deep tissue compression, knuckles whitened");
        POSITIVE_SUBSTITUTIONS.put(
                Pattern.compile("(?i)\\b(detached|severed|truncated)\\s+(limbs?|hands?|arms?)\\b"),
                "limbs continuous into shoulder and hip sockets");
    }

    // Bounded so a stray construction cannot swallow the remainder of a clause.
    private static final String NEGATION_BODY = "((?:(?!\\bbecause\\b|\\bdue to\\b|\\binstead\\b|\\bwhile\\b|\\bwhen\\b|\\bso that\\b|\\bin order to\\b)[^;.,]){1,120})";

    private static final List<Pattern> NEGATION_PATTERNS = List.of(
            Pattern.compile("(?i)\\bavoid(?:ing)?\\s+" + NEGATION_BODY),
            Pattern.compile("(?i)\\bwithout\\s+" + NEGATION_BODY),
            Pattern.compile("(?i)\\bdo\\s+not\\s+" + NEGATION_BODY),
            Pattern.compile("(?i)\\bno\\s+(?:more\\s+)?" + NEGATION_BODY),
            Pattern.compile("(?i)\\bnever\\s+" + NEGATION_BODY)
    );

    /**
     * Flag detection inside prose. The value run is bounded at six tokens and stops at a
     * sentence terminator, so a stray flag cannot consume the descriptive text after it.
     */
    private static final Pattern PROSE_FLAG_CLEANUP = Pattern.compile(
            "(?<![-\\w])--[A-Za-z][\\w-]*(?:\\s+(?!--)[^\\s]*[^\\s.]){0,6}", Pattern.CASE_INSENSITIVE);

    /** Flag detection inside an actual parameter string. Value run is unbounded. */
    private static final Pattern FLAG_PARSE = Pattern.compile(
            "(--[A-Za-z][\\w-]*)\\s*((?:(?!--).)*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[-\u2013\u2014\\s,;.]+$");
    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[-\u2013\u2014\\s,;.]+");

    public static final String DEFAULT_MJ_FLAGS = "--ar 16:9 --style raw --v 6.1";

    // ---------------------------------------------------------------------
    // Compilation
    // ---------------------------------------------------------------------

    public static CompiledOutput compile(SceneContract contract, EngineProfile profile, String requestedFlags) {
        Objects.requireNonNull(contract, "SceneContract cannot be null");
        Objects.requireNonNull(profile, "EngineProfile cannot be null");

        SceneContract.PromptClauses clauses = contract.promptClauses();

        // Phase 1 — volumetric geometry, head of the attention window.
        List<String> geometry = new ArrayList<>();
        addFragment(geometry, clauses == null ? null : clauses.subjectClause());
        addFragment(geometry, clauses == null ? null : clauses.actionClause());
        geometry.addAll(contactFragments(contract.interactionDynamics()));
        geometry.addAll(surrealFragments(contract.surrealMountings()));
        addFragment(geometry, clauses == null ? null : clauses.spatialClause());

        // Degraded extraction: fall back to the raw action summary rather than emitting nothing.
        if (geometry.isEmpty()) {
            addFragment(geometry, contract.dramaticAction());
        }

        // Phase 2 — surface, material, optics. Tail position; safe to truncate.
        List<String> surface = new ArrayList<>();
        addFragment(surface, clauses == null ? null : clauses.surfaceClause());

        String positive = switch (profile) {
            case MIDJOURNEY_V6 -> joinCompact(geometry, surface);
            case FLUX_1_DEV -> joinProse(geometry, surface);
        };

        String negatives = collectNegatives(contract.surrealMountings());

        String flags = switch (profile) {
            case MIDJOURNEY_V6 -> mergeFlags(blankTo(requestedFlags, DEFAULT_MJ_FLAGS), negatives);
            case FLUX_1_DEV -> "";
        };

        return new CompiledOutput(profile, positive, negatives, flags, regionalPrompts(contract));
    }

    private static void addFragment(List<String> target, String raw) {
        String normalized = normalizeFragment(raw);
        if (!normalized.isEmpty()) {
            target.add(normalized);
        }
    }

    /**
     * Removal order matters: flags and banned tokens are stripped first, delimiter debris is
     * collapsed afterwards, and edge punctuation is trimmed last.
     */
    private static String normalizeFragment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = PROSE_FLAG_CLEANUP.matcher(raw).replaceAll("");
        s = enforcePositivePhrasing(s);
        s = stripBannedTokens(s);
        s = collapseEmptySegments(s);
        s = LEADING_PUNCTUATION.matcher(s).replaceAll("");
        s = TRAILING_PUNCTUATION.matcher(s).replaceAll("");
        return s.trim();
    }

    private static String joinCompact(List<String> geometry, List<String> surface) {
        List<String> all = new ArrayList<>(geometry);
        all.addAll(surface);
        String joined = String.join(", ", all).trim();
        return joined.isEmpty() ? "" : joined + ".";
    }

    private static String joinProse(List<String> geometry, List<String> surface) {
        StringBuilder sb = new StringBuilder();
        String head = String.join(", ", geometry).trim();
        if (!head.isEmpty()) {
            sb.append(capitalize(head)).append(".");
        }
        String tail = String.join(", ", surface).trim();
        if (!tail.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(capitalize(tail)).append(".");
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------------
    // Fragment sources
    // ---------------------------------------------------------------------

    private static List<String> contactFragments(SceneContract.InteractionDynamics dynamics) {
        if (dynamics == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        addFragment(out, dynamics.contactPointCoordinate());
        addFragment(out, dynamics.mutualTensionVector());
        addFragment(out, dynamics.anatomicalCommitment());
        return out;
    }

    private static List<String> surrealFragments(List<SceneContract.SurrealMounting> mountings) {
        if (mountings == null || mountings.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (SceneContract.SurrealMounting m : mountings) {
            if (m == null) {
                continue;
            }
            String primitive = normalizeFragment(m.geometricPrimitive());
            if (primitive.isEmpty()) {
                primitive = normalizeFragment(m.originalNoun());
            }
            String verb = normalizeFragment(m.surfaceMountingVerb());

            if (!primitive.isEmpty() && !verb.isEmpty()) {
                out.add(primitive + ", " + verb);
            } else if (!primitive.isEmpty()) {
                out.add(primitive);
            } else if (!verb.isEmpty()) {
                out.add(verb);
            }
        }
        return out;
    }

    private static List<RegionalPrompt> regionalPrompts(SceneContract contract) {
        List<SceneContract.RegionalPass> passes = contract.regionalPasses();
        if (passes == null || passes.isEmpty()) {
            return List.of();
        }
        List<RegionalPrompt> out = new ArrayList<>();
        for (SceneContract.RegionalPass pass : passes) {
            if (pass == null) {
                continue;
            }
            String prompt = normalizeFragment(pass.isolatedPrompt());
            if (prompt.isEmpty()) {
                continue;
            }
            out.add(new RegionalPrompt(
                    pass.targetZone() == null ? "[unspecified]" : pass.targetZone().name(),
                    blankTo(pass.boundingDescription(), "[unspecified]"),
                    prompt + "."
            ));
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------
    // Negation handling
    // ---------------------------------------------------------------------

    static String enforcePositivePhrasing(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String result = input;
        for (Pattern pattern : NEGATION_PATTERNS) {
            result = rewriteNegation(result, pattern);
        }
        for (Map.Entry<Pattern, String> entry : POSITIVE_SUBSTITUTIONS.entrySet()) {
            result = entry.getKey().matcher(result).replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        return collapseEmptySegments(result).trim();
    }

    private static String rewriteNegation(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String captured = matcher.group(1);
            String positive = lookupPositive(captured);
            // Eşleşen pozitif ikame yoksa cümlenin kalanını yutmak yerine nesneyi koru.
            String replacement = (positive != null) ? positive : captured.trim();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String lookupPositive(String captured) {
        if (captured == null) {
            return null;
        }
        for (Map.Entry<Pattern, String> entry : POSITIVE_SUBSTITUTIONS.entrySet()) {
            if (entry.getKey().matcher(captured).find()) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Flag handling
    // ---------------------------------------------------------------------

    static String mergeFlags(String base, String extraNegatives) {
        Map<String, String> flags = new LinkedHashMap<>();

        Matcher matcher = FLAG_PARSE.matcher(base == null ? "" : base);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            String value = matcher.group(2) == null ? "" : matcher.group(2).trim();
            flags.merge(key, value, PromptCompiler::joinValues);
        }

        if (extraNegatives != null && !extraNegatives.isBlank()) {
            flags.merge("--no", extraNegatives.trim(), PromptCompiler::joinValues);
        }

        if (flags.containsKey("--no")) {
            flags.put("--no", dedupeCsv(flags.get("--no")));
        }

        return flags.entrySet().stream()
                .map(e -> e.getValue().isBlank() ? e.getKey() : e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(" "))
                .trim();
    }

    private static String joinValues(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + ", " + b;
    }

    private static String collectNegatives(List<SceneContract.SurrealMounting> mountings) {
        if (mountings == null || mountings.isEmpty()) {
            return "";
        }
        String raw = mountings.stream()
                .filter(Objects::nonNull)
                .map(SceneContract.SurrealMounting::requiredNegatives)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(", "));
        return dedupeCsv(raw);
    }

    private static String dedupeCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return "";
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String cleaned = part.trim().toLowerCase();
            if (!cleaned.isEmpty()) {
                seen.add(cleaned);
            }
        }
        return String.join(", ", seen);
    }

    // ---------------------------------------------------------------------
    // Text hygiene
    // ---------------------------------------------------------------------

    public static String purgeLexicalPolicy(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return collapseEmptySegments(stripBannedTokens(input));
    }

    private static String stripBannedTokens(String input) {
        if (input == null) {
            return "";
        }
        String sanitized = input;
        for (Pattern pattern : BANNED_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("");
        }
        return sanitized;
    }

    static String collapseEmptySegments(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([,;.])", "$1")
                .replaceAll("([,;])(?:\\s*[,;])+", "$1")
                .replaceAll("(?:^|(?<=\\s))(?:a|an|the|with|and|of|to)\\s*(?=[,;.])", "")
                .replaceAll("^[\\s,;]+", "")
                .replaceAll("[\\s,;]+$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    public static String compileRegionalManifest(CompiledOutput output) {
        Objects.requireNonNull(output, "CompiledOutput cannot be null");
        if (!output.hasRegionalPrompts()) {
            return "[No regional passes staged for this scene.]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===========================================================================\n");
        sb.append("  REGIONAL / INPAINT MANIFEST\n");
        sb.append("===========================================================================\n");

        int index = 1;
        for (RegionalPrompt pass : output.regionalPrompts()) {
            sb.append("[PASS ").append(index).append("] Zone: ").append(pass.zone()).append("\n");
            sb.append("  Bounding : ").append(pass.boundingDescription()).append("\n");
            sb.append("  Prompt   : ").append(pass.prompt()).append("\n");
            sb.append("---------------------------------------------------------------------------\n");
            index++;
        }
        return sb.toString().stripTrailing();
    }

    public static String generateStructuralReport(SceneContract c) {
        Objects.requireNonNull(c, "SceneContract cannot be null");
        Objects.requireNonNull(c.cameraRig(), "cameraRig is required by schema but was null");
        Objects.requireNonNull(c.environmentalOptics(), "environmentalOptics is required by schema but was null");

        StringBuilder sb = new StringBuilder();

        sb.append("[PHASE 1: VOLUMETRIC GEOMETRY]\n");
        sb.append("  - Action Summary  : ").append(blankTo(c.dramaticAction(), "[unspecified]")).append("\n");
        sb.append("  - Viewport & Lens : ")
                .append(blankTo(c.cameraRig().viewportAngle(), "[unspecified]")).append(" | ")
                .append(blankTo(c.cameraRig().focalLength(), "[unspecified]")).append(" (")
                .append(blankTo(c.cameraRig().cameraDistance(), "[unspecified]")).append(")\n");
        sb.append("  - Frame Offset    : ")
                .append(c.cameraRig().primarySubjectOffset() == null ? "[unspecified]" : c.cameraRig().primarySubjectOffset())
                .append("\n");

        if (c.subjectStance() == null) {
            sb.append("  - Subject Stance  : [None - subjectless scene]\n");
        } else {
            sb.append("  - Stance & Vector : ")
                    .append(blankTo(c.subjectStance().facingVector(), "[unspecified]")).append(" | ")
                    .append(blankTo(c.subjectStance().poseDynamics(), "[unspecified]")).append("\n");
            sb.append("  - Center of Mass  : ")
                    .append(blankTo(c.subjectStance().centerOfGravity(), "[unspecified]")).append("\n");
        }

        if (c.kineticAnchors() == null) {
            sb.append("  - Kinetic Anchors : [None - static scene]\n");
        } else {
            sb.append("  - Physical Origin : ").append(blankTo(c.kineticAnchors().exactOriginPoint(), "[unspecified]")).append("\n");
            sb.append("  - Kinetic Vector  : ").append(blankTo(c.kineticAnchors().forceTrajectory(), "[unspecified]")).append("\n");
            sb.append("  - Impact Area     : ").append(blankTo(c.kineticAnchors().physicalImpactArea(), "[unspecified]")).append("\n");
        }

        if (c.interactionDynamics() == null) {
            sb.append("  - Interaction     : [None - single subject or non-contact]\n");
        } else {
            sb.append("  - Contact Point   : ").append(blankTo(c.interactionDynamics().contactPointCoordinate(), "[unspecified]")).append("\n");
            sb.append("  - Tension Vector  : ").append(blankTo(c.interactionDynamics().mutualTensionVector(), "[unspecified]")).append("\n");
            sb.append("  - Anatomy Lock    : ")
                    .append(blankTo(c.interactionDynamics().anatomicalCommitment(), "[unspecified]"))
                    .append("\n");
        }

        if (c.surrealMountings() == null || c.surrealMountings().isEmpty()) {
            sb.append("  - Surreal Mounts  : [None]\n");
        } else {
            sb.append("  - Surreal Mounts  :\n");
            for (SceneContract.SurrealMounting m : c.surrealMountings()) {
                if (m == null) {
                    continue;
                }
                sb.append("      * ").append(blankTo(m.originalNoun(), "[unspecified]"))
                        .append(" -> ").append(blankTo(m.geometricPrimitive(), "[unspecified]"))
                        .append("  [negatives: ").append(blankTo(m.requiredNegatives(), "none")).append("]\n");
            }
        }

        sb.append("\n[PHASE 2: SURFACE & ENVIRONMENTAL OPTICS]\n");
        sb.append("  - Primary Light   : ").append(blankTo(c.environmentalOptics().primaryLightSource(), "[unspecified]")).append("\n");
        sb.append("  - Edge Rim Light  : ").append(blankTo(c.environmentalOptics().rimLight(), "[unspecified]")).append("\n");
        sb.append("  - Shutter & Atmos : ")
                .append(blankTo(c.environmentalOptics().shutterSpeed(), "[unspecified]")).append(" | ")
                .append(blankTo(c.environmentalOptics().atmosphericParticulates(), "[unspecified]")).append("\n");

        return sb.toString().stripTrailing();
    }
}