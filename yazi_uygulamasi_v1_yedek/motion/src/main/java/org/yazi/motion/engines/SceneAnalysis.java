package org.yazi.motion.engines;

import org.yazi.motion.domain.ImperfectionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Scene-level decisions derived from the description: lighting tone, camera energy, the main focus for the
 * negative prompt's focus budget, user suppress areas, and the imperfection phrases for the positive prompt.
 */
public final class SceneAnalysis {
    private SceneAnalysis() {}

    public enum Tone { SOFT, HARD }

    public enum Energy { HIGH, LOW }

    public enum Focus {
        FACIAL_EXPRESSION("facial expression", List.of("highly detailed background", "busy background patterns",
                "sharp background focus", "intricate clothing texture", "elaborate hair accessories")),
        CHARACTER_MOVEMENT("character movement", List.of("highly detailed background", "intricate pavement texture",
                "complex environment detail", "elaborate sky texture")),
        OBJECT("object detail", List.of("cluttered background", "distracting props", "intricate backdrop texture",
                "complex environment detail")),
        ENVIRONMENT("environment", List.of("detailed foreground figures", "intricate character detail",
                "busy foreground clutter", "elaborate costume texture"));

        private final String label;
        private final List<String> negatives;

        Focus(String label, List<String> negatives) {
            this.label = label;
            this.negatives = negatives;
        }

        public String label() {
            return label;
        }

        public List<String> negatives() {
            return negatives;
        }
    }

    /** Which terms protect the focus budget, and why. {@code source} is "user" or "auto". */
    public record FocusBudget(String source, Optional<Focus> focus, List<String> areas, List<String> terms) {
        public FocusBudget {
            areas = List.copyOf(areas);
            terms = List.copyOf(terms);
        }
    }

    public static final Map<ImperfectionLevel, List<String>> IMPERFECTION_PHRASES = Map.of(
            ImperfectionLevel.LIGHT, List.of("light film grain", "minimal lens distortion", "subtle chromatic aberration"),
            ImperfectionLevel.PRONOUNCED, List.of("light film grain", "minimal lens distortion", "subtle chromatic aberration",
                    "handheld camera shake", "visible film scratches", "organic lighting flicker"));

    /** "slow motion" is a pacing effect (often used in action), not a calm scene. */
    private static final Pattern SLOW_MOTION = Pattern.compile("slow[\\s-]?(?:motion|mo)\\b");
    private static final Pattern AREA_SPLIT = Pattern.compile("(?i),|;|\\s+(?:or|and|veya|ya da|ve)\\s+");

    public static Optional<Tone> tone(String description) {
        String n = Lexicon.normalize(description);
        int soft = Lexicon.countMatches(n, Lexicon.SOFT_TONE), hard = Lexicon.countMatches(n, Lexicon.HARD_TONE);
        return hard > soft ? Optional.of(Tone.HARD) : soft > hard ? Optional.of(Tone.SOFT) : Optional.empty();
    }

    public static Optional<Energy> energy(String description) {
        String n = SLOW_MOTION.matcher(Lexicon.normalize(description)).replaceAll(" ");
        int high = Lexicon.countMatches(n, Lexicon.HIGH_ENERGY), low = Lexicon.countMatches(n, Lexicon.LOW_ENERGY);
        return high > low ? Optional.of(Energy.HIGH) : low > high ? Optional.of(Energy.LOW) : Optional.empty();
    }

    /** Main focus of the scene, or empty when nothing stands out. Ties favour the tighter subject. */
    public static Optional<Focus> detectFocus(String description) {
        String n = Lexicon.normalize(description);
        boolean hasActor = CueExtractor.findSubject(description == null ? "" : description, Lexicon.ACTORS).isPresent();
        int motion = Lexicon.countMatches(n, Lexicon.FOCUS_MOTION);
        Map<Focus, Integer> scores = Map.of(
                Focus.FACIAL_EXPRESSION, Lexicon.countMatches(n, Lexicon.FOCUS_FACE),
                Focus.CHARACTER_MOVEMENT, hasActor || motion > 0 ? motion + (hasActor ? 1 : 0) : 0,
                Focus.OBJECT, Lexicon.countMatches(n, Lexicon.FOCUS_OBJECT),
                Focus.ENVIRONMENT, Lexicon.countMatches(n, Lexicon.FOCUS_ENVIRONMENT));
        Focus best = null;
        for (Focus f : Focus.values()) {
            if (scores.get(f) > 0 && (best == null || scores.get(f) > scores.get(best))) best = f;
        }
        return Optional.ofNullable(best);
    }

    /** "background, sky, or ground texture" -> [background, sky, ground]. */
    public static List<String> parseSuppressAreas(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : AREA_SPLIT.split(VendorSyntax.strip(text))) {
            String area = part.trim().toLowerCase(Locale.ROOT)
                    .replaceFirst("^(?:the|a|an)\\s+", "")
                    .replaceFirst("\\s+(?:textures?|details?|detailing)$", "")
                    .trim();
            if (!area.isEmpty() && !out.contains(area)) out.add(area);
        }
        return out;
    }

    /** "highly detailed [area], intricate [area] texture" for every area, then "elaborate [area]" for every area. */
    public static List<String> suppressPhrases(List<String> areas) {
        List<String> out = new ArrayList<>();
        for (String a : areas) {
            out.add("highly detailed " + a);
            out.add("intricate " + a + " texture");
        }
        for (String a : areas) out.add("elaborate " + a);
        return out;
    }

    /** User-entered areas replace the automatic focus decision; blank means automatic. */
    public static FocusBudget focusBudget(String description, List<String> userAreas) {
        if (userAreas != null && !userAreas.isEmpty()) {
            return new FocusBudget("user", Optional.empty(), userAreas, suppressPhrases(userAreas));
        }
        Optional<Focus> focus = detectFocus(description);
        return new FocusBudget("auto", focus, List.of(), focus.map(Focus::negatives).orElse(List.of()));
    }
}
