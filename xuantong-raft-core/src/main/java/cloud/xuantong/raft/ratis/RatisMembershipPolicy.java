package cloud.xuantong.raft.ratis;

import java.time.Duration;

/** Safety limits for one explicit Raft membership operation. */
public record RatisMembershipPolicy(
        boolean allowSingleNode,
        Duration catchUpTimeout,
        long maximumCatchUpGap) {

    public RatisMembershipPolicy {
        if (catchUpTimeout == null || catchUpTimeout.isZero()
                || catchUpTimeout.isNegative()) {
            throw new IllegalArgumentException("catchUpTimeout must be positive");
        }
        if (maximumCatchUpGap < 0) {
            throw new IllegalArgumentException("maximumCatchUpGap must not be negative");
        }
    }

    public static RatisMembershipPolicy productionDefaults() {
        return new RatisMembershipPolicy(false, Duration.ofMinutes(2), 0L);
    }
}
