package org.yazi.motion.engines;

import org.yazi.motion.domain.StylePreferences;
import org.yazi.motion.domain.VisualStyleMatrix;
import org.yazi.motion.domain.VisualStylePreset;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Build step 06: explicit overrides > cues inferred from the description > default constants. */
public final class CinematicStyleMatrixEngine implements ICinematicStyleMatrixEngine {

    public static final VisualStylePreset DEFAULT_PRESET = VisualStylePreset.CINEMATIC_35MM;
    public static final double DEFAULT_LENS_MM = 35;
    public static final String DEFAULT_LIGHTING = "High contrast cinematic volumetric key lighting";
    public static final List<String> DEFAULT_PALETTE = List.of("#1A1A1A", "#D4AF37", "#0A192F");
    public static final double DEFAULT_ATMOSPHERE = 0.35;
    public static final double DEFAULT_FPS = 24;

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");

    @Override
    public VisualStyleMatrix resolveStyleMatrix(StylePreferences explicitPreferences, String contextDescription) {
        StylePreferences prefs = explicitPreferences == null ? StylePreferences.none() : explicitPreferences;
        String ctx = Lexicon.normalize(contextDescription);

        Optional<VisualStylePreset> inferredPreset = Lexicon.firstMatch(ctx, Lexicon.PRESET);
        Optional<String> time = Lexicon.firstMatch(ctx, Lexicon.TIME_OF_DAY);
        Optional<String> mood = Lexicon.firstMatch(ctx, Lexicon.MOOD);
        List<String> weather = Lexicon.allMatches(ctx, Lexicon.WEATHER);

        VisualStylePreset preset = prefs.preset() != null ? prefs.preset() : inferredPreset.orElse(DEFAULT_PRESET);
        double lens = positive(prefs.lensMm()) ? prefs.lensMm() : DEFAULT_LENS_MM;
        Optional<SceneAnalysis.Tone> tone = SceneAnalysis.tone(contextDescription);
        String lighting = prefs.lightingProfile() != null && !prefs.lightingProfile().isBlank()
                ? prefs.lightingProfile().trim()
                : tone.isPresent() ? toneLighting(tone.get(), time, weather) : lighting(preset, time, mood, weather);
        List<String> palette = normalizePalette(prefs.colorPalette());
        if (palette.isEmpty()) palette = palette(preset, time, mood);
        double density = prefs.atmosphericDensity() != null && !prefs.atmosphericDensity().isNaN()
                ? Math.min(1.0, Math.max(0.0, prefs.atmosphericDensity()))
                : density(weather);
        double fps = positive(prefs.fps()) ? prefs.fps() : DEFAULT_FPS;

        return new VisualStyleMatrix(preset, lens, lighting, palette, density, fps);
    }

    private static boolean positive(Double v) {
        return v != null && v > 0 && Double.isFinite(v);
    }

    /** Hex codes become upper-case #RRGGBB, names Title Case; blanks and duplicates are dropped. */
    public static List<String> normalizePalette(List<String> colors) {
        if (colors == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String c : colors) {
            if (c == null || c.isBlank()) continue;
            String v = c.trim();
            if (HEX.matcher(v).matches()) {
                String hex = v.substring(1).toUpperCase(Locale.ROOT);
                if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
                out.add("#" + hex);
            } else {
                out.add(titleCase(v));
            }
        }
        return List.copyOf(out);
    }

    static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    static String lighting(VisualStylePreset preset, Optional<String> time, Optional<String> mood, List<String> weather) {
        String base = time.map(t -> switch (t) {
            case "Neon Night" -> "Hard neon practicals with colored rim light and wet reflections";
            case "Golden Hour" -> "Warm low-angle golden hour sunlight with long soft shadows";
            case "Blue Hour" -> "Cool blue hour ambient skylight with glowing practicals";
            case "Dawn" -> "Soft diffused sunrise light with pale warm highlights";
            case "Night" -> "Low-key moonlit night lighting with pools of practical light";
            case "Midday" -> "Crisp overhead midday sunlight with defined shadows";
            case "Overcast Day" -> "Even diffused overcast daylight with low contrast";
            default -> null;
        }).orElseGet(() -> switch (preset) {
            case NEO_NOIR -> "Hard low-key chiaroscuro lighting with slatted shadows";
            case MVT_CYBERPUNK -> "Saturated neon key and rim lighting with haze";
            case ANIME -> "Clean cel-shaded key light with crisp rim highlights";
            case DOCUMENTARY -> "Natural available light";
            case CINEMATIC_35MM, HYPERREALISTIC_8K -> DEFAULT_LIGHTING;
        });
        String moodMod = mood.map(m -> switch (m) {
            case "Tense" -> ", tense chiaroscuro shadows";
            case "Melancholic" -> ", muted and desaturated";
            case "Epic" -> ", volumetric god rays and strong silhouettes";
            case "Mysterious" -> ", deep shadows with atmospheric haze";
            case "Romantic" -> ", warm glow with soft bloom";
            case "Joyful" -> ", bright and airy with lifted shadows";
            case "Calm" -> ", gentle falloff";
            default -> "";
        }).orElse("");
        return base + moodMod + weatherLighting(weather);
    }

    static String weatherLighting(List<String> weather) {
        StringBuilder w = new StringBuilder();
        for (String effect : weather) {
            w.append(switch (effect) {
                case "Fog" -> ", volumetric fog diffusion";
                case "Rain", "Heavy Rain" -> ", rain-slicked reflective surfaces";
                case "Snowfall" -> ", soft light scattering through falling snow";
                case "Dust Storm" -> ", dusty backlit haze";
                case "Wind" -> ", wind-blown particles";
                default -> "";
            });
        }
        return w.toString();
    }

    /** Lighting decision from scene tone + time of day, e.g. "sunset, peaceful" -> soft golden hour diffusion. */
    static String toneLighting(SceneAnalysis.Tone tone, Optional<String> time, List<String> weather) {
        String t = time.orElse("");
        String base;
        if (tone == SceneAnalysis.Tone.SOFT) {
            String source = switch (t) {
                case "Golden Hour" -> "golden hour";
                case "Dawn" -> "sunrise";
                case "Blue Hour" -> "blue hour";
                case "Night" -> "moonlit";
                case "Neon Night" -> "neon";
                case "Midday" -> "daylight";
                case "Overcast Day" -> "overcast";
                default -> "natural";
            };
            String fill = List.of("Night", "Blue Hour", "Neon Night").contains(t) ? "cool" : "warm";
            base = "soft " + source + " diffusion, low contrast, " + fill + " fill light";
        } else {
            String key = switch (t) {
                case "Golden Hour", "Dawn" -> "hard low-sun key light";
                case "Midday" -> "hard overhead key light";
                case "Neon Night" -> "hard neon key light";
                default -> "hard single-source key light";
            };
            String rim = switch (t) {
                case "Golden Hour", "Dawn" -> "warm amber";
                case "Neon Night" -> "magenta neon";
                default -> "cold blue";
            };
            base = key + ", deep shadows, " + rim + " rim";
        }
        return base + weatherLighting(weather);
    }

    static List<String> palette(VisualStylePreset preset, Optional<String> time, Optional<String> mood) {
        if (time.isPresent()) {
            switch (time.get()) {
                case "Neon Night": return List.of("#FF2E88", "#00E5FF", "#1A1033", "#0B0B12");
                case "Golden Hour": return List.of("#F4A259", "#E76F51", "#FFD6A5", "#2B2D42");
                case "Blue Hour": return List.of("#1D3557", "#457B9D", "#A8DADC", "#F1FAEE");
                case "Dawn": return List.of("#FFCDB2", "#FFB4A2", "#B5838D", "#6D6875");
                case "Night": return List.of("#0D1B2A", "#1B263B", "#415A77", "#E0E1DD");
                default: break;
            }
        }
        return switch (preset) {
            case MVT_CYBERPUNK -> List.of("#FF2E88", "#00E5FF", "#1A1033", "#0B0B12");
            case NEO_NOIR -> List.of("#0B0B0B", "#3A3A3A", "#B5B5B5", "#8C1C13");
            case ANIME -> List.of("#FFB7C5", "#7EC8E3", "#FFF1C1", "#2E2E48");
            default -> mood.map(m -> switch (m) {
                case "Melancholic" -> List.of("#6B7B8C", "#A3B1BF", "#D9D9D9", "#3A3F44");
                case "Joyful" -> List.of("#FFD166", "#06D6A0", "#118AB2", "#FFFFFF");
                default -> DEFAULT_PALETTE;
            }).orElse(DEFAULT_PALETTE);
        };
    }

    static double density(List<String> weather) {
        double d = DEFAULT_ATMOSPHERE;
        for (String w : weather) {
            d = Math.max(d, switch (w) {
                case "Fog" -> 0.7;
                case "Dust Storm" -> 0.65;
                case "Heavy Rain" -> 0.55;
                case "Rain", "Snowfall" -> 0.45;
                default -> DEFAULT_ATMOSPHERE;
            });
        }
        return d;
    }
}
