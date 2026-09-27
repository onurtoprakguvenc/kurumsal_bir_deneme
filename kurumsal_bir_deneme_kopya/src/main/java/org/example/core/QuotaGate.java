package org.example.core;

import java.time.Instant;

/**
 * Port: guards outbound AI calls. Local ingestion, indexing and BM25 search never consult it, so the zero-cost
 * path always stays available when the AI budget is gone.
 */
public interface QuotaGate {

    /** Permission for exactly one model call. */
    record Lease(boolean granted, Denial denial, int remaining, Instant retryAt) {

        public static Lease granted(int remaining) {
            return new Lease(true, null, remaining, null);
        }

        public static Lease denied(Denial denial, Instant retryAt, int remaining) {
            return new Lease(false, denial, remaining, retryAt);
        }
    }

    /** Why a call was refused. */
    enum Denial {
        DAILY_QUOTA_EXHAUSTED,
        RATE_LIMITED,
        OUTSIDE_BUSINESS_HOURS
    }

    /** Current counters, for {@code status} style reporting. */
    record Snapshot(int used, int limit, int remaining, long promptTokens, long outputTokens, int calls,
                    Instant resetAt, boolean open, String schedule) {
    }

    /** Reserves one call; a denied lease must not be followed by a model request. */
    Lease acquire();

    /** Reports token usage of a completed call. */
    void recordUsage(long promptTokens, long outputTokens);

    Snapshot snapshot();

    /** Gate that never blocks (used when quota enforcement is disabled). */
    static QuotaGate unlimited() {
        return new QuotaGate() {
            @Override
            public Lease acquire() {
                return Lease.granted(Integer.MAX_VALUE);
            }

            @Override
            public void recordUsage(long promptTokens, long outputTokens) {
                // nothing to track
            }

            @Override
            public Snapshot snapshot() {
                return new Snapshot(0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0, null, true, "unlimited");
            }
        };
    }
}
