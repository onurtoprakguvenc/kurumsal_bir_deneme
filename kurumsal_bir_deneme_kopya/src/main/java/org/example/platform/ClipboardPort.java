package org.example.platform;

/**
 * Where "copy path" / "copy content" put their text. The JavaFX chassis backs it with the system clipboard; headless
 * runners substitute a recorder, so the copy routines are verified without a toolkit.
 */
@FunctionalInterface
public interface ClipboardPort {
    void putText(String text);
}
