package org.example.state;

/**
 * Handle returned by every listener registration in the state layer. Closing it removes the listener, so a view
 * that is detached or disposed can drop its hooks deterministically instead of leaking through a strong reference
 * held by a long-lived coordinator. Closing twice is harmless.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
