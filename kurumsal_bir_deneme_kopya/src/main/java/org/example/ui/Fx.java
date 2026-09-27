package org.example.ui;

import javafx.application.Platform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FX-thread plumbing shared by the views. Every coordinator in the state layer notifies on the thread that caused the
 * change (a virtual search thread, the terminal thread, a project switch), so each view hops through here.
 */
final class Fx {

    private Fx() {
    }

    /** Runs {@code task} now when already on the FX thread, otherwise on the next pulse. */
    static void run(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /**
     * Collapses any number of {@link #request()} calls between two pulses into one run of the task on the FX thread,
     * so a burst of terminal lines or streamed tokens costs one layout pass instead of hundreds.
     */
    static final class Coalescer {
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final Runnable task;

        Coalescer(Runnable task) {
            this.task = task;
        }

        void request() {
            if (scheduled.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    scheduled.set(false);
                    task.run();
                });
            }
        }
    }
}
