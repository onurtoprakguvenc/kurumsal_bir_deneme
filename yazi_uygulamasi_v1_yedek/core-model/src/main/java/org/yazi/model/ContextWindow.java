package org.yazi.model;

/**
 * The only buffer text a model ever sees for one operation.
 *
 * @param before          text immediately before the target (or the caret)
 * @param target          the text being transformed or analysed; empty for a continuation
 * @param after           text immediately after the target (or the caret)
 * @param brief           short notes the user pinned for this document (characters, glossary); never model output
 * @param beforeTruncated true when {@code before} starts somewhere after the beginning of the document
 */
public record ContextWindow(String before, String target, String after, String brief, boolean beforeTruncated) {

    public ContextWindow {
        before = (before == null) ? "" : before;
        target = (target == null) ? "" : target;
        after = (after == null) ? "" : after;
        brief = (brief == null) ? "" : brief;
    }

    public int totalChars() {
        return before.length() + target.length() + after.length() + brief.length();
    }
}
