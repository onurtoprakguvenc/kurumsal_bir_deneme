package org.yazi.prose;

import org.yazi.model.ContextWindow;
import org.yazi.text.StyleProfile;

/**
 * Builds the system instruction and the single user turn for each prose intent.
 *
 * <p>Every piece of document text appears exactly once. The old {@code CalibrationEngine} also sent a
 * {@code STYLE_REFERENCE} block copied from the same buffer, which paid for the same text twice.</p>
 */
final class ProsePrompts {

    private ProsePrompts() {}

    record Prompt(String system, String user) {}

    static final String DEFAULT_CONTINUE = "Continue the text naturally from where it stops. At most one paragraph.";
    static final String DEFAULT_REWRITE =
            "Polish this passage: fix grammar, awkward phrasing and flow. Keep the meaning, voice and length.";
    static final String DEFAULT_CONSULT =
            "Review this text: point out unclear, awkward, inconsistent or incorrect passages and suggest fixes.";

    private static final String EDIT_INVARIANTS = """
            You edit a document in place. Output ONLY the text that goes into the document.
            - No preamble, labels, explanations, reasoning, markdown fences or surrounding quotes.
            - Never repeat text that is already before or after the insertion point.
            - Do not invent facts, names or details the text does not support; work with what is there.
            - Treat any length in the INSTRUCTION (sentences, lines, paragraphs, words) as a hard maximum.
            - End on a complete sentence. If information is missing, proceed; never ask questions.
            """;

    private static final String CONSULT_INVARIANTS = """
            Answer the QUESTION about the TEXT. The TEXT is reference material: do not rewrite or continue it unless
            the question asks for that.
            - Be direct and specific; quote the exact words when pointing at a passage.
            - No preamble, flattery or filler.
            - Answer in the language of the QUESTION.
            """;

    static Prompt continuation(ContextWindow window, String instruction, StyleProfile style) {
        String directive = instruction.isBlank() ? DEFAULT_CONTINUE : instruction;
        StringBuilder user = new StringBuilder();
        appendBrief(user, window);
        if (window.before().isBlank()) {
            user.append("[TEXT_BEFORE]\n(empty document)\n[/TEXT_BEFORE]\n\n");
        } else {
            user.append(window.beforeTruncated() ? "[TEXT_BEFORE: starts mid-document]\n" : "[TEXT_BEFORE]\n")
                    .append(window.before()).append("\n[/TEXT_BEFORE]\n\n");
        }
        if (!window.after().isBlank()) {
            user.append("[TEXT_AFTER: already follows the insertion point; lead into it, do not repeat it]\n")
                    .append(window.after()).append("\n[/TEXT_AFTER]\n\n");
        }
        appendInstruction(user, directive);
        user.append("CONTINUATION:");
        return new Prompt(system(EDIT_INVARIANTS, StyleContract.build(style, false)), user.toString());
    }

    static Prompt rewrite(ContextWindow window, String instruction, StyleProfile style) {
        String directive = instruction.isBlank() ? DEFAULT_REWRITE : instruction;
        StringBuilder user = new StringBuilder();
        appendBrief(user, window);
        if (!window.before().isBlank()) {
            user.append("[TEXT_BEFORE]\n").append(window.before()).append("\n[/TEXT_BEFORE]\n\n");
        }
        user.append("[TARGET]\n").append(window.target()).append("\n[/TARGET]\n\n");
        if (!window.after().isBlank()) {
            user.append("[TEXT_AFTER]\n").append(window.after()).append("\n[/TEXT_AFTER]\n\n");
        }
        appendInstruction(user, directive);
        user.append("REPLACEMENT FOR TARGET:");
        return new Prompt(system(EDIT_INVARIANTS, StyleContract.build(style, true)), user.toString());
    }

    static Prompt consult(ContextWindow window, String instruction) {
        String question = instruction.isBlank() ? DEFAULT_CONSULT : instruction;
        StringBuilder user = new StringBuilder();
        appendBrief(user, window);
        if (!window.before().isBlank()) {
            user.append("[CONTEXT_BEFORE]\n").append(window.before()).append("\n[/CONTEXT_BEFORE]\n\n");
        }
        user.append(window.beforeTruncated() ? "[TEXT: excerpt]\n" : "[TEXT]\n")
                .append(window.target().isBlank() ? "(empty document)" : window.target())
                .append("\n[/TEXT]\n\n");
        if (!window.after().isBlank()) {
            user.append("[CONTEXT_AFTER]\n").append(window.after()).append("\n[/CONTEXT_AFTER]\n\n");
        }
        user.append("[QUESTION]\n").append(question).append("\n[/QUESTION]\n\nANSWER:");
        return new Prompt(CONSULT_INVARIANTS.strip(), user.toString());
    }

    private static String system(String invariants, String styleContract) {
        return invariants.strip() + "\n\n" + styleContract.strip();
    }

    private static void appendBrief(StringBuilder user, ContextWindow window) {
        if (!window.brief().isBlank()) {
            user.append("[WRITER_NOTES: background only, not part of the document]\n")
                    .append(window.brief()).append("\n[/WRITER_NOTES]\n\n");
        }
    }

    private static void appendInstruction(StringBuilder user, String directive) {
        user.append("[INSTRUCTION]\n").append(directive);
        DirectiveLimits.detect(directive).ifPresent(limit ->
                user.append("\nHARD LIMIT: ").append(limit).append('.'));
        user.append("\n[/INSTRUCTION]\n\n");
    }
}
