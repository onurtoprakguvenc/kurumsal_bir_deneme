package org.yazi.text;

import java.util.regex.Pattern;

/**
 * Recognises the terse structural operators a writer can type instead of a sentence. Routing between continue,
 * rewrite and consult is decided by the UI gesture, not by guessing from the wording; this class only answers
 * "is this directive an edit operator?".
 */
public final class DirectiveSyntax {

    private DirectiveSyntax() {}

    /** sed style: {@code s/old/new/} or {@code s/old/new/g}. */
    private static final Pattern SED = Pattern.compile("^s/[^/]+/[^/]*/?[a-z]*$");

    /** Short arrow notation on one line: {@code sword -> blade}, {@code kılıç => bıçak}. */
    private static final Pattern ARROW = Pattern.compile("^[^\\n\\r]{1,50}\\s*(?:->|=>)\\s*[^\\n\\r]{1,50}$");

    public static boolean isStructuralOperator(String directive) {
        if (directive == null) {
            return false;
        }
        String d = directive.strip();
        return !d.isEmpty() && (SED.matcher(d).matches() || ARROW.matcher(d).matches());
    }
}
