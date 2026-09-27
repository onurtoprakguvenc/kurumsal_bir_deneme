package org.yazi.motion.engines;

import org.yazi.motion.domain.CameraAngle;
import org.yazi.motion.domain.CameraKinematics;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.MovementSpeed;
import org.yazi.motion.domain.ShotType;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.exception.PromptEngineException;
import org.yazi.motion.domain.exception.TemporalDecompositionException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.yazi.motion.engines.TemporalContinuity.*;

/**
 * Build step 05.
 * <ul>
 *   <li>One unit per narrative beat; a beat with conflicting pacing cues becomes one unit per cue, in written order.</li>
 *   <li>Segment count stays within [ceil(d/5), max(1, floor(d/2))], so every segment lasts 2-5 s
 *       (surplus beats merge, missing ones become continuation shots).</li>
 *   <li>Durations follow narrative velocity: proportional to word count, clamped to [2, 5] via bisection.</li>
 *   <li>Boundaries are rounded to milliseconds; the last boundary is exactly d, absorbing any drift.</li>
 * </ul>
 */
public final class TemporalKinematicDecomposer implements ITemporalKinematicDecomposer {

    private final CueExtractor cueExtractor = new CueExtractor();

    private record Slot(String text, Optional<String> subject, CueExtractor.Beat cues, MotionPacing pacing,
                        String phaseLabel, double weight, int part, int parts) {
    }

    @Override
    public CompletableFuture<List<TemporalSegment>> decomposeTimeline(ParsedIntentResult intent) {
        try {
            return CompletableFuture.completedFuture(decompose(intent));
        } catch (PromptEngineException ex) {
            return CompletableFuture.failedFuture(ex);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return CompletableFuture.failedFuture(new TemporalDecompositionException(
                    "Timeline decomposition failed: " + ex.getMessage(), Map.of(), ex));
        }
    }

    private List<TemporalSegment> decompose(ParsedIntentResult intent) {
        double d = intent.targetDurationSec();
        CueExtractor.Cues cues = cueExtractor.extract(intent.sanitizedDescription());
        Double explicitLens = intent.explicitStylePreferences().lensMm();
        boolean useExplicitLens = explicitLens != null && explicitLens > 0 && Double.isFinite(explicitLens);

        // scene energy sets the cutting rhythm: fast cuts for intense scenes, long takes for calm ones
        Optional<SceneAnalysis.Energy> energy = SceneAnalysis.energy(intent.sanitizedDescription());
        double minSeg = energy.map(e -> e == SceneAnalysis.Energy.HIGH ? 1.5 : 4.0).orElse(MIN_SEGMENT_SEC);
        double maxSeg = energy.map(e -> e == SceneAnalysis.Energy.HIGH ? 3.0 : 8.0).orElse(MAX_SEGMENT_SEC);

        List<Slot> units = toUnits(cues.beats());
        int nMin = (int) Math.ceil(d / maxSeg);
        int nMax = Math.max(1, (int) Math.floor(d / minSeg));
        List<Slot> slots = units.size() > nMax ? merge(units, nMax) : units.size() < nMin ? extend(units, nMin) : units;

        int n = slots.size();
        double[] durations = allocate(slots.stream().mapToDouble(Slot::weight).toArray(), d, minSeg, maxSeg);
        double[] bounds = new double[n + 1];
        double cumulative = 0;
        for (int i = 1; i < n; i++) {
            cumulative += durations[i - 1];
            bounds[i] = roundToMillis(cumulative);
        }
        bounds[n] = d;

        List<TemporalSegment> segments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Slot slot = slots.get(i);
            Optional<SceneAnalysis.Energy> slotEnergy = slot.cues().energy().or(() -> energy);
            MotionPacing pacing = slot.pacing() != null ? slot.pacing()
                    : slotEnergy.map(e -> e == SceneAnalysis.Energy.HIGH ? MotionPacing.FAST_PACED : MotionPacing.SLOW_PACED)
                            .orElse(MotionPacing.REALTIME);
            CameraKinematics kinematics = kinematics(slot, i, n, pacing, useExplicitLens ? explicitLens : null, slotEnergy);
            segments.add(new TemporalSegment(i, bounds[i], bounds[i + 1], roundToMillis(bounds[i + 1] - bounds[i]),
                    description(slot, cues.primarySubject()), kinematics,
                    pacingNote(pacing, slot.cues().angle(), bounds[i], bounds[i + 1])));
        }

        double sum = segments.stream().mapToDouble(TemporalSegment::durationSec).sum();
        if (Math.abs(sum - d) > TOLERANCE_SEC * n) {
            throw new TemporalDecompositionException("Segment durations do not sum to the target duration.",
                    Map.of("sum_of_segments", sum, "target_duration", d));
        }
        assertTemporalContinuity(segments, d);
        return List.copyOf(segments);
    }

    private static List<Slot> toUnits(List<CueExtractor.Beat> beats) {
        List<Slot> units = new ArrayList<>();
        for (CueExtractor.Beat b : beats) {
            double words = Math.max(1, b.wordCount());
            int k = b.pacings().size();
            if (k <= 1) {
                units.add(new Slot(b.text(), b.subject(), b, k == 1 ? b.pacings().getFirst() : null, null, words, 0, 1));
            } else {
                for (int j = 0; j < k; j++) {
                    MotionPacing p = b.pacings().get(j);
                    units.add(new Slot(b.text(), b.subject(), b, p, label(p) + " phase " + (j + 1) + "/" + k, words / k, 0, 1));
                }
            }
        }
        return units;
    }

    private static List<Slot> merge(List<Slot> units, int n) {
        List<Slot> out = new ArrayList<>(n);
        int u = units.size();
        for (int g = 0; g < n; g++) {
            List<Slot> group = units.subList((int) Math.round((double) g * u / n), (int) Math.round((double) (g + 1) * u / n));
            if (group.size() == 1) {
                out.add(group.getFirst());
                continue;
            }
            String text = String.join("; then ", group.stream().map(Slot::text).distinct().toList());
            Optional<String> subject = group.stream().map(Slot::subject).flatMap(Optional::stream).findFirst();
            MotionPacing pacing = group.stream().map(Slot::pacing).filter(p -> p != null).findFirst().orElse(null);
            CueExtractor.Beat first = group.getFirst().cues();
            CueExtractor.Beat cues = new CueExtractor.Beat(first.text(), first.subject(),
                    group.stream().map(s -> s.cues().movement()).flatMap(Optional::stream).findFirst(),
                    group.stream().map(s -> s.cues().shot()).flatMap(Optional::stream).findFirst(),
                    group.stream().map(s -> s.cues().angle()).flatMap(Optional::stream).findFirst(),
                    group.stream().map(s -> s.cues().energy()).flatMap(Optional::stream).findFirst(),
                    first.pacings(), first.wordCount());
            out.add(new Slot(text, subject, cues, pacing, null,
                    group.stream().mapToDouble(Slot::weight).sum(), 0, 1));
        }
        return out;
    }

    private static List<Slot> extend(List<Slot> units, int n) {
        int[] parts = new int[units.size()];
        Arrays.fill(parts, 1);
        for (int total = units.size(); total < n; total++) {
            int best = 0;
            for (int i = 1; i < units.size(); i++) {
                if (units.get(i).weight() / parts[i] > units.get(best).weight() / parts[best]) best = i;
            }
            parts[best]++;
        }
        List<Slot> out = new ArrayList<>(n);
        for (int i = 0; i < units.size(); i++) {
            Slot s = units.get(i);
            for (int p = 0; p < parts[i]; p++) {
                out.add(new Slot(s.text(), s.subject(), s.cues(), s.pacing(), s.phaseLabel(), s.weight() / parts[i], p, parts[i]));
            }
        }
        return out;
    }

    /** Solves sum(clamp(lambda * w_i, min, max)) = d by bisection; the sum is monotone in lambda. */
    static double[] allocate(double[] weights, double d, double minSeg, double maxSeg) {
        int n = weights.length;
        if (n == 1) return new double[]{d};
        double lo = Math.min(minSeg, d / n), hi = Math.max(maxSeg, d / n);
        double a = 0, b = 1;
        while (clampedSum(weights, b, lo, hi) < d && b < 1e12) b *= 2;
        for (int it = 0; it < 200; it++) {
            double mid = (a + b) / 2;
            if (clampedSum(weights, mid, lo, hi) < d) a = mid; else b = mid;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = Math.clamp(b * weights[i], lo, hi);
        return out;
    }

    private static double clampedSum(double[] w, double lambda, double lo, double hi) {
        double s = 0;
        for (double x : w) s += Math.clamp(lambda * x, lo, hi);
        return s;
    }

    private static String description(Slot slot, String globalSubject) {
        String t = slot.text().trim().replaceAll("[\\s.;,]+$", "");
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        if (slot.phaseLabel() != null) t += " (" + slot.phaseLabel() + ")";
        if (slot.parts() > 1) {
            String phase = slot.part() == 0 ? "begins" : slot.part() == slot.parts() - 1 ? "completes" : "continues and develops";
            t += " (action " + phase + ", part " + (slot.part() + 1) + "/" + slot.parts() + ")";
        }
        return t + ". Focus: " + slot.subject().orElse(globalSubject);
    }

    private static String pacingNote(MotionPacing pacing, Optional<CameraAngle> angle, double start, double end) {
        String note = label(pacing) + " pacing from " + PromptPackageCompiler.fmt(start) + "s to " + PromptPackageCompiler.fmt(end) + "s";
        return angle.map(a -> note + "; " + a.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)).orElse(note);
    }

    /**
     * High energy: rotating coverage, TRACKING / HANDHELD_SHAKE, FAST. Low energy: held frames, STATIC / CRANE_UP, SLOW.
     * Otherwise establish -> engage -> detail -> resolve. Explicit framing, angle and movement in the beat always win.
     */
    private static CameraKinematics kinematics(Slot slot, int index, int n, MotionPacing pacing, Double explicitLens,
                                               Optional<SceneAnalysis.Energy> energy) {
        ShotType shot;
        CameraMovement movement;
        MovementSpeed speed;
        if (energy.isPresent() && energy.get() == SceneAnalysis.Energy.HIGH) {
            shot = List.of(ShotType.MEDIUM_SHOT, ShotType.CLOSE_UP, ShotType.WIDE_SHOT).get(index % 3);
            movement = index % 2 == 0 ? CameraMovement.TRACKING : CameraMovement.HANDHELD_SHAKE;
            speed = MovementSpeed.FAST;
        } else if (energy.isPresent()) {
            shot = index % 2 == 0 ? ShotType.WIDE_SHOT : ShotType.MEDIUM_SHOT;
            movement = index % 2 == 0 ? CameraMovement.STATIC : CameraMovement.CRANE_UP;
            speed = MovementSpeed.SLOW;
        } else {
            if (n == 1) {
                shot = ShotType.MEDIUM_SHOT; movement = CameraMovement.ZOOM_IN; speed = MovementSpeed.SLOW;
            } else if (index == 0) {
                shot = ShotType.WIDE_SHOT; movement = CameraMovement.PAN_RIGHT; speed = MovementSpeed.SLOW;
            } else if (index == n - 1) {
                shot = ShotType.WIDE_SHOT; movement = CameraMovement.ZOOM_OUT; speed = MovementSpeed.SLOW;
            } else if (index % 2 == 1) {
                shot = ShotType.MEDIUM_SHOT; movement = CameraMovement.TRACKING; speed = MovementSpeed.MODERATE;
            } else {
                shot = ShotType.CLOSE_UP; movement = CameraMovement.STATIC; speed = MovementSpeed.SLOW;
            }
            if (slot.part() > 0) {
                shot = switch (shot) {
                    case WIDE_SHOT -> ShotType.MEDIUM_SHOT;
                    case MEDIUM_SHOT -> ShotType.CLOSE_UP;
                    default -> shot;
                };
            }
        }
        // explicit framing and angle in the description are assigned directly to this segment
        shot = slot.cues().shot().orElse(shot);
        ShotType finalShot = shot;
        CameraMovement fallbackMovement = movement;
        movement = slot.cues().movement().orElseGet(() ->
                finalShot == ShotType.EXTREME_CLOSE_UP || finalShot == ShotType.MACRO ? CameraMovement.STATIC : fallbackMovement);
        CameraAngle angle = slot.cues().angle().orElse(shot == ShotType.AERIAL ? CameraAngle.HIGH_ANGLE : CameraAngle.EYE_LEVEL);
        speed = switch (pacing) {
            case SLOW_MOTION, TIMELAPSE, SLOW_PACED -> MovementSpeed.SLOW;
            case FAST_PACED, SPEED_RAMP -> MovementSpeed.FAST;
            case REALTIME -> speed;
        };
        double focal = explicitLens != null ? explicitLens : switch (shot) {
            case AERIAL -> 16;
            case WIDE_SHOT -> 24;
            case MEDIUM_SHOT -> 35;
            case CLOSE_UP -> 85;
            case EXTREME_CLOSE_UP, MACRO -> 100;
        };
        return new CameraKinematics(shot, angle, movement, speed, focal);
    }

    static String label(MotionPacing p) {
        return switch (p) {
            case SLOW_MOTION -> "Slow motion";
            case REALTIME -> "Realtime";
            case FAST_PACED -> "Fast-paced";
            case SLOW_PACED -> "Slow-paced";
            case TIMELAPSE -> "Timelapse";
            case SPEED_RAMP -> "Speed ramp";
        };
    }
}
