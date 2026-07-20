package cloud.xuantong.client.util;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe warning gate that keeps repeated background failures from turning
 * into an unbounded log stream while retaining the number of suppressed events.
 */
public final class WarningRateLimiter {
    private final long intervalNanos;
    private final LongSupplier nanoTime;

    private boolean initialized;
    private long lastAllowedNanos;
    private long suppressed;

    public WarningRateLimiter(Duration interval) {
        this(interval, System::nanoTime);
    }

    WarningRateLimiter(Duration interval, LongSupplier nanoTime) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.intervalNanos = interval.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public synchronized Decision acquire() {
        long now = nanoTime.getAsLong();
        if (!initialized || now - lastAllowedNanos >= intervalNanos) {
            long suppressedSinceLast = suppressed;
            suppressed = 0L;
            initialized = true;
            lastAllowedNanos = now;
            return new Decision(true, suppressedSinceLast);
        }
        if (suppressed < Long.MAX_VALUE) {
            suppressed++;
        }
        return new Decision(false, 0L);
    }

    public record Decision(boolean allowed, long suppressedSinceLast) {
    }
}
