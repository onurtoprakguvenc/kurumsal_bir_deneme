package org.yazi.desktop;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Runs test code on the JavaFX thread. Starts the toolkit once; no window is ever shown. */
final class Fx {

    private static boolean started;

    private Fx() {}

    static synchronized void start() {
        if (!started) {
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
            started = true;
        }
    }

    static <T> T call(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("FX action failed", e);
        }
    }

    static void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** Polls {@code condition} on the FX thread until it holds, failing after five seconds. */
    static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (call(condition::getAsBoolean)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
