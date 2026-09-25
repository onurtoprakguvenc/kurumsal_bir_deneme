package org.example.core;

import org.example.core.AnswerModel.Turn;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, RAM-only conversation window. Holds at most {@value #MAX_TURNS} completed turns; older turns fall
 * off. Nothing is ever serialized or written to disk, and {@link #clear()} forgets everything.
 */
public final class EphemeralContext {

    public static final int MAX_TURNS = 3;
    /** Stored answers are clipped so the window cannot grow the prompt without bound. */
    private static final int MAX_STORED_ANSWER_CHARS = 6_000;
    private static final int MAX_STORED_QUESTION_CHARS = 2_000;

    private final Deque<Turn> turns = new ArrayDeque<>(MAX_TURNS + 1);

    public synchronized void record(String question, String answer) {
        turns.addLast(new Turn(clip(question, MAX_STORED_QUESTION_CHARS), clip(answer, MAX_STORED_ANSWER_CHARS)));
        while (turns.size() > MAX_TURNS) {
            turns.removeFirst();
        }
    }

    public synchronized List<Turn> snapshot() {
        return List.copyOf(turns);
    }

    public synchronized int size() {
        return turns.size();
    }

    public synchronized void clear() {
        turns.clear();
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + " […]";
    }
}
