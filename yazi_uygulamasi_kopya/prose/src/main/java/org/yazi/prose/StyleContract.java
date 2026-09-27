package org.yazi.prose;

import org.yazi.text.StyleProfile;

import java.util.Locale;

/**
 * Turns measured style into short, domain-neutral instructions, so the output matches the writer's text instead
 * of drifting towards the model's average voice. Ported from {@code DynamicStyleContractBuilder}, condensed to
 * keep the fixed per-call overhead small.
 *
 * <p>General-purpose only: nothing here assumes fiction. Narrative contracts (observer rules, physical
 * skeletons) belong to the separate fiction project and are not part of this editor's defaults.</p>
 */
final class StyleContract {

    private StyleContract() {}

    private static final double SHORT_SENTENCES = 7.0;
    private static final double LONG_SENTENCES = 16.0;
    private static final double UNIFORM_METER = 1.0;
    private static final double HIGH_VARIANCE = 4.5;

    static String build(StyleProfile style, boolean replacingTarget) {
        StringBuilder out = new StringBuilder("[STYLE: match the existing text]\n");
        out.append("- Write in the same language as the text. Never translate.\n");

        if (style.isMeasured()) {
            double avg = style.avgWordsPerSentence();
            if (avg <= SHORT_SENTENCES) {
                out.append("- Sentences: short and direct, few subordinate clauses.\n");
            } else if (avg >= LONG_SENTENCES) {
                out.append("- Sentences: long, multi-clause constructions are normal here; keep that momentum.\n");
            } else {
                out.append("- Sentences: medium length, mixing compound and simple sentences.\n");
            }

            double spread = style.sentenceLengthStdDev();
            if (spread <= UNIFORM_METER) {
                out.append("- Rhythm: keep the steady, uniform sentence length.\n");
            } else if (spread >= HIGH_VARIANCE) {
                out.append("- Rhythm: alternate clearly between short and long sentences, as the text does.\n");
            }

            if (style.lineDelimited()) {
                out.append("- Layout: one unit per line (quoted lines or list items); do not merge into paragraphs.\n");
            }
            if (style.inlineParentheticals()) {
                out.append("- Parenthetical asides inside sentences are part of this style.\n");
            }
            if (style.lowercaseSentenceStarts()) {
                out.append("- Keep the writer's lowercase sentence starts; do not capitalise them.\n");
            }
            if (style.gluedPunctuation()) {
                out.append("- Keep the writer's habit of no space after punctuation (\"end.next\").\n");
            }
            out.append(String.format(Locale.ROOT, "- Measured: %.1f words per sentence, spread %.1f.%n",
                    avg, spread));
        }

        if (replacingTarget) {
            out.append("[SCOPE: replace TARGET only]\n")
                    .append("- Change only what the instruction asks for; keep meaning, names and key terms.\n")
                    .append("- Keep roughly the same length unless the instruction says otherwise.\n")
                    .append("- The replacement must read seamlessly with the text before and after it.\n");
        }
        return out.toString();
    }
}
