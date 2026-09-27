package org.yazi.gateway;

/** Receives streamed text fragments in order. Called on the thread that runs the request. */
@FunctionalInterface
public interface TokenSink {

    void accept(String fragment);

    TokenSink DISCARD = fragment -> { };
}
