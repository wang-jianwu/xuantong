package cloud.xuantong.registry.state;

public record LeaseRenewal(
        LeaseReference lease,
        long renewSequence,
        long ttlMs) {

    public LeaseRenewal {
        if (lease == null) {
            throw new IllegalArgumentException("lease must not be null");
        }
        if (renewSequence < 1 || ttlMs < 1) {
            throw new IllegalArgumentException("renewSequence and ttlMs must be positive");
        }
    }
}
