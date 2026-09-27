package org.yazi.gateway;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation shared between the UI (which calls {@link #cancel()}) and a running request.
 * Callbacks run at most once, on the cancelling thread, or immediately if registered after cancellation.
 */
public final class CancellationToken {

    /** Returned by {@link #onCancel}; closing it unregisters the callback. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Once> callbacks = new CopyOnWriteArrayList<>();

    /** A fresh token nobody else holds, for callers that never cancel. */
    public static CancellationToken none() {
        return new CancellationToken();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Once callback : callbacks) {
                callback.run();
            }
            callbacks.clear();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public Registration onCancel(Runnable action) {
        Once once = new Once(action);
        callbacks.add(once);
        if (cancelled.get()) {
            once.run();
        }
        return () -> callbacks.remove(once);
    }

    private static final class Once implements Runnable {
        private final Runnable action;
        private final AtomicBoolean done = new AtomicBoolean();

        Once(Runnable action) {
            this.action = action;
        }

        @Override
        public void run() {
            if (done.compareAndSet(false, true)) {
                action.run();
            }
        }
    }
}
