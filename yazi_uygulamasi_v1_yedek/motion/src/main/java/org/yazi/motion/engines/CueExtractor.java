package org.yazi.motion.engines;

import org.yazi.motion.domain.CameraAngle;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.ShotType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Splits a description into narrative beats and extracts the explicit cinematic cues in each. */
public final class CueExtractor {

    public static final int MAX_BEATS = 12;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "of", "in", "on", "at", "to", "and", "with", "bir", "ve", "ile", "bu", "şu");
    private static final Pattern LEADING_CONNECTOR = Pattern.compile(
            "(?i)^(?:and then|and|then|so|but|finally|after that|afterwards|meanwhile|ve|daha sonra|sonra|ardından|en sonunda|sonunda)[,]?\\s+");
    /** "10 second", "15-second", "10 saniyelik" — the length belongs in target_duration_sec, not in the shot text. */
    private static final Pattern DURATION_PREFIX = Pattern.compile(
            "(?i)^(?:an?\\s+)?\\d+(?:[.,]\\d+)?\\s*[- ]?(?:seconds?|secs?|s|saniyelik|saniye)\\b\\s*");

    public record Beat(String text, Optional<String> subject, Optional<CameraMovement> movement, Optional<ShotType> shot,
                       Optional<CameraAngle> angle, Optional<SceneAnalysis.Energy> energy, List<MotionPacing> pacings,
                       int wordCount) {
        public Beat {
            pacings = List.copyOf(pacings);
        }
    }

    public record Cues(String primarySubject, List<Beat> beats) {
        public Cues {
            beats = List.copyOf(beats);
        }
    }

    public Cues extract(String description) {
        String globalSubject = findSubjects(description, Lexicon.ACTORS)
                .or(() -> findSubjects(description, Lexicon.PLACES))
                .orElse("the main subject");

        List<Beat> beats = new ArrayList<>();
        Optional<String> lastActor = Optional.empty();

        for (String text : splitBeats(description)) {
            String n = Lexicon.normalize(text);
            Optional<String> actor = findSubjects(text, Lexicon.ACTORS);
            if (actor.isPresent()) lastActor = actor;

            Optional<String> subject = actor.isPresent() ? actor : lastActor;
            if (subject.isEmpty()) subject = findSubjects(text, Lexicon.PLACES);

            beats.add(new Beat(text, subject,
                    Lexicon.firstMatch(n, Lexicon.MOVEMENT),
                    Lexicon.firstMatch(n, Lexicon.SHOT),
                    Lexicon.firstMatch(n, Lexicon.ANGLE),
                    SceneAnalysis.energy(text),
                    Lexicon.matchesInTextOrder(n, Lexicon.PACING),
                    text.split("\\s+").length));
        }

        return new Cues(globalSubject, beats);
    }

    static Optional<String> findSubjects(String text, List<String> lexicon) {
        String[] tokens = text.split("\\s+");
        List<String> foundSubjects = new ArrayList<>();

        for (int i = 0; i < tokens.length; i++) {
            String word = Lexicon.normalize(tokens[i]).replaceAll("[^\\p{L}\\p{N}'-]", "");
            for (String s : lexicon) {
                boolean ascii = s.matches("[a-z]+");
                if (word.equals(s) || word.equals(s + "s") || word.equals(s + "es")
                        || (!ascii && word.startsWith(s) && word.length() <= s.length() + 4)) {
                    String subject = tokens[i].replaceAll("[^\\p{L}\\p{N}'-]", "");
                    if (i > 0) {
                        String prev = tokens[i - 1].replaceAll("[^\\p{L}\\p{N}'-]", "");
                        if (!prev.isEmpty() && !STOPWORDS.contains(Lexicon.normalize(prev))) subject = prev + " " + subject;
                    }
                    String norm = Lexicon.normalize(subject);
                    if (!foundSubjects.contains(norm)) {
                        foundSubjects.add(norm);
                    }
                    break;
                }
            }
        }

        if (foundSubjects.isEmpty()) return Optional.empty();
        return Optional.of(String.join(" and ", foundSubjects));
    }

    static List<String> splitBeats(String description) {
        List<String> out = new ArrayList<>();
        for (String part : Lexicon.BEAT_SPLIT.split(description)) {
            String p = LEADING_CONNECTOR.matcher(part.trim()).replaceFirst("").trim();
            p = DURATION_PREFIX.matcher(p).replaceFirst("").trim();
            p = p.replaceAll("^[,:\\-–]+\\s*", "").trim();
            if (p.length() >= 2) out.add(p);
        }
        if (out.isEmpty()) out.add(description.trim());
        if (out.size() > MAX_BEATS) {
            List<String> head = new ArrayList<>(out.subList(0, MAX_BEATS - 1));
            head.add(String.join("; ", out.subList(MAX_BEATS - 1, out.size())));
            out = head;
        }
        return out;
    }

    static Optional<String> findSubject(String text, List<String> lexicon) {
        String[] tokens = text.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String word = Lexicon.normalize(tokens[i]).replaceAll("[^\\p{L}\\p{N}'-]", "");
            for (String s : lexicon) {
                boolean ascii = s.matches("[a-z]+");
                if (word.equals(s) || word.equals(s + "s") || word.equals(s + "es")
                        || (!ascii && word.startsWith(s) && word.length() <= s.length() + 4)) {
                    String subject = tokens[i].replaceAll("[^\\p{L}\\p{N}'-]", "");
                    if (i > 0) {
                        String prev = tokens[i - 1].replaceAll("[^\\p{L}\\p{N}'-]", "");
                        if (!prev.isEmpty() && !STOPWORDS.contains(Lexicon.normalize(prev))) subject = prev + " " + subject;
                    }
                    return Optional.of(Lexicon.normalize(subject));
                }
            }
        }
        return Optional.empty();
    }
}
