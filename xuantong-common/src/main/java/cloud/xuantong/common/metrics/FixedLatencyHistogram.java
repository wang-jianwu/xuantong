package cloud.xuantong.common.metrics;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Thread-safe fixed-bucket latency histogram with bounded memory usage. */
public final class FixedLatencyHistogram {
    private final long[] upperBoundsMillis;
    private final long[] upperBoundsNanos;
    private final AtomicLongArray cumulativeCounts;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    public FixedLatencyHistogram(long... upperBoundsMillis) {
        if (upperBoundsMillis == null || upperBoundsMillis.length == 0) {
            throw new IllegalArgumentException("Latency histogram requires buckets");
        }
        this.upperBoundsMillis = upperBoundsMillis.clone();
        this.upperBoundsNanos = new long[upperBoundsMillis.length];
        long previous = 0L;
        for (int i = 0; i < upperBoundsMillis.length; i++) {
            long bound = upperBoundsMillis[i];
            if (bound <= previous) {
                throw new IllegalArgumentException(
                        "Latency histogram buckets must be positive and increasing");
            }
            this.upperBoundsNanos[i] = TimeUnit.MILLISECONDS.toNanos(bound);
            previous = bound;
        }
        this.cumulativeCounts = new AtomicLongArray(upperBoundsMillis.length);
    }

    public void recordMillis(long durationMillis) {
        recordNanos(TimeUnit.MILLISECONDS.toNanos(Math.max(0L, durationMillis)));
    }

    public void recordNanos(long durationNanos) {
        long bounded = Math.max(0L, durationNanos);
        count.incrementAndGet();
        totalNanos.getAndUpdate(current -> saturatingAdd(current, bounded));
        for (int i = 0; i < upperBoundsNanos.length; i++) {
            if (bounded <= upperBoundsNanos[i]) {
                cumulativeCounts.incrementAndGet(i);
            }
        }
    }

    public Snapshot snapshot() {
        long[] counts = new long[cumulativeCounts.length()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = cumulativeCounts.get(i);
        }
        return new Snapshot(
                count.get(),
                totalNanos.get() / 1_000_000_000D,
                upperBoundsMillis,
                counts);
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public record Snapshot(
            long count,
            double totalSeconds,
            long[] upperBoundsMillis,
            long[] cumulativeCounts) {
        public Snapshot {
            if (count < 0L || totalSeconds < 0D) {
                throw new IllegalArgumentException("Latency totals must not be negative");
            }
            upperBoundsMillis = upperBoundsMillis == null
                    ? new long[0] : upperBoundsMillis.clone();
            cumulativeCounts = cumulativeCounts == null
                    ? new long[0] : cumulativeCounts.clone();
            if (upperBoundsMillis.length != cumulativeCounts.length) {
                throw new IllegalArgumentException(
                        "Latency bucket bounds and counts must have equal length");
            }
            long previous = 0L;
            for (int i = 0; i < cumulativeCounts.length; i++) {
                if (cumulativeCounts[i] < previous || cumulativeCounts[i] > count) {
                    throw new IllegalArgumentException(
                            "Latency bucket counts must be cumulative");
                }
                previous = cumulativeCounts[i];
            }
        }

        @Override
        public long[] upperBoundsMillis() {
            return upperBoundsMillis.clone();
        }

        @Override
        public long[] cumulativeCounts() {
            return cumulativeCounts.clone();
        }

        @Override
        public String toString() {
            return "Snapshot[count=" + count
                    + ", totalSeconds=" + totalSeconds
                    + ", upperBoundsMillis=" + Arrays.toString(upperBoundsMillis)
                    + ", cumulativeCounts=" + Arrays.toString(cumulativeCounts) + ']';
        }
    }
}
