package org.yazi.motion.engines;

/**
 * Inverted domain filter (EN + TR): reject only what is clearly NOT a video request, accept everything else
 * as a scene. High bar for rejection (an explicit non-video pattern must match), low bar for acceptance.
 */
public final class DomainClassifier {

    public record Verdict(boolean videoDomain, String reason) {
    }

    public Verdict classify(String description) {
        String text = Lexicon.normalize(description);
        boolean videoFraming = Lexicon.matchesAny(text, Lexicon.VIDEO_FRAMING);
        for (int i = 0; i < Lexicon.NON_VIDEO_REQUESTS.size(); i++) {
            if (Lexicon.NON_VIDEO_REQUESTS.get(i).matcher(text).find()) {
                // Pattern 0 is an explicit "write me an X" command and is always out of scope; the others may
                // legitimately appear inside a scene ("a programmer typing source code") when framed as video.
                if (i == 0 || !videoFraming) {
                    return new Verdict(false, "requests non-video output (text, code, prose or audio)");
                }
            }
        }
        return new Verdict(true, videoFraming ? "explicit video framing" : "treated as a scene description");
    }
}
