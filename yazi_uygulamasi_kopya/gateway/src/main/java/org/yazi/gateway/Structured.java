package org.yazi.gateway;

/** A parsed structured-output value plus the token usage of the request that produced it. */
public record Structured<T>(T value, Usage usage) {
}
