package org.yazi.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * Up/Down recall for the instruction bar, like a shell history. Pure and FX-free.
 *
 * <p>Kept in memory for the session only and never sent to the model: it is a typing aid, not context. The line
 * being typed when the writer starts browsing is kept as a draft and comes back after the newest entry.</p>
 */
final class InstructionHistory {

    static final int MAX_ENTRIES = 50;

    private final List<String> entries = new ArrayList<>();
    /** Position while browsing; {@code entries.size()} means "at the draft". */
    private int cursor;
    private String draft = "";

    /** Remembers a used instruction (stripped). Blank ones are ignored; a repeat moves to the newest position. */
    void record(String instruction) {
        String s = instruction == null ? "" : instruction.strip();
        if (!s.isEmpty()) {
            entries.remove(s);
            entries.add(s);
            if (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
        }
        cursor = entries.size();
        draft = "";
    }

    /** The older entry, or {@code current} unchanged when there is none. */
    String previous(String current) {
        if (cursor == entries.size()) {
            draft = current == null ? "" : current;
        }
        if (cursor == 0) {
            return entries.isEmpty() ? draft : entries.get(0);
        }
        cursor--;
        return entries.get(cursor);
    }

    /** The newer entry, or the saved draft after the newest one. */
    String next(String current) {
        if (cursor >= entries.size()) {
            return current == null ? "" : current;
        }
        cursor++;
        return cursor == entries.size() ? draft : entries.get(cursor);
    }

    List<String> entries() {
        return List.copyOf(entries);
    }
}
