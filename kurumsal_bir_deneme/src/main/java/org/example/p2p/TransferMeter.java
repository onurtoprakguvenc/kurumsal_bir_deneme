package org.example.p2p;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free {@link FileTransferService.Progress} sink. The transfer thread only stores two longs per block; any other
 * thread (a renderer, a UI poller) reads a consistent-enough {@link Snapshot} with percentage, rate and ETA
 * whenever it likes, so slow consoles can never throttle the transfer.
 *
 * <p>The rate counts bytes moved in this session only: the first update marks the baseline, so a resumed transfer
 * does not report its already-present prefix as speed.</p>
 */
public final class TransferMeter implements FileTransferService.Progress {

    /**
     * @param transferred    bytes present at the receiver (resumed prefix included)
     * @param total          file size, or {@code -1} before the first update
     * @param bytesPerSecond average rate of this session, 0 until measurable
     * @param etaSeconds     estimated seconds left, {@code -1} when unknown
     */
    public record Snapshot(long transferred, long total, double bytesPerSecond, long etaSeconds, long elapsedMillis) {

        public boolean started() {
            return total >= 0;
        }

        public double percent() {
            return total <= 0 ? (total == 0 ? 100.0 : 0.0) : transferred * 100.0 / total;
        }

        public double megabytesPerSecond() {
            return bytesPerSecond / (1024.0 * 1024.0);
        }

        public boolean done() {
            return total >= 0 && transferred >= total;
        }
    }

    private final AtomicLong transferred = new AtomicLong();
    private final AtomicLong total = new AtomicLong(-1);
    private final long createdNanos = System.nanoTime();
    private volatile long baselineBytes = -1;
    private volatile long baselineNanos;

    @Override
    public void update(long transferredBytes, long totalBytes) {
        if (baselineBytes < 0) {
            baselineNanos = System.nanoTime();
            baselineBytes = transferredBytes;
        }
        total.set(totalBytes);
        transferred.set(transferredBytes);
    }

    public Snapshot snapshot() {
        long now = System.nanoTime();
        long done = transferred.get();
        long size = total.get();
        long base = baselineBytes;
        double rate = 0;
        long eta = -1;
        if (base >= 0) {
            double seconds = (now - baselineNanos) / 1e9;
            if (seconds > 0.05 && done > base) {
                rate = (done - base) / seconds;
                eta = size > done ? (long) Math.ceil((size - done) / rate) : 0;
            }
        }
        return new Snapshot(done, size, rate, eta, (now - createdNanos) / 1_000_000);
    }
}
