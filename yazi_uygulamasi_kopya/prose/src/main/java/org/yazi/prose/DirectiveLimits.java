package org.yazi.prose;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds an explicit length limit in the writer's instruction ("1 paragraph", "2-3 sentences", "3 cümle") so it
 * can be restated to the model as a hard maximum. Ported from {@code AcousticVoiceContract.detectVolumeCeiling}.
 */
final class DirectiveLimits {

    private DirectiveLimits() {}

    private static final Pattern VOLUME = Pattern.compile(
            "(\\d{1,3})\\s*(?:[-–]\\s*(\\d{1,3})\\s*)?"
                    + "(sentences?|lines?|paragraphs?|words?|cümle|satır|paragraf|kelime)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** For example {@code "at most 3 sentences"}; empty when the instruction states no limit. */
    static Optional<String> detect(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return Optional.empty();
        }
        Matcher m = VOLUME.matcher(instruction);
        if (!m.find()) {
            return Optional.empty();
        }
        String upper = (m.group(2) != null) ? m.group(2) : m.group(1);
        return Optional.of("at most " + upper + " " + m.group(3).toLowerCase(Locale.forLanguageTag("tr")));
    }
}
