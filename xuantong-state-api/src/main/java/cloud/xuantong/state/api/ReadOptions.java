package cloud.xuantong.state.api;

import java.time.Duration;

public record ReadOptions(
        ReadConsistency consistency,
        StateRevision minimumRevision,
        Duration maxStaleness) {

    public ReadOptions {
        if (consistency == null) {
            throw new IllegalArgumentException("consistency must not be null");
        }
        maxStaleness = maxStaleness == null ? Duration.ZERO : maxStaleness;
        if (maxStaleness.isNegative()) {
            throw new IllegalArgumentException("maxStaleness must not be negative");
        }
        if (consistency == ReadConsistency.LINEARIZABLE && !maxStaleness.isZero()) {
            throw new IllegalArgumentException(
                    "LINEARIZABLE reads must not declare maxStaleness");
        }
        if (consistency == ReadConsistency.BOUNDED_STALE) {
            if (minimumRevision == null) {
                throw new IllegalArgumentException(
                        "BOUNDED_STALE reads require minimumRevision");
            }
            if (maxStaleness.isZero()) {
                throw new IllegalArgumentException(
                        "BOUNDED_STALE reads require positive maxStaleness");
            }
        }
    }

    public static ReadOptions linearizable() {
        return new ReadOptions(ReadConsistency.LINEARIZABLE, null, Duration.ZERO);
    }

    public static ReadOptions linearizable(StateRevision minimumRevision) {
        return new ReadOptions(
                ReadConsistency.LINEARIZABLE, minimumRevision, Duration.ZERO);
    }

    public static ReadOptions boundedStale(
            StateRevision minimumRevision, Duration maxStaleness) {
        return new ReadOptions(
                ReadConsistency.BOUNDED_STALE, minimumRevision, maxStaleness);
    }
}
