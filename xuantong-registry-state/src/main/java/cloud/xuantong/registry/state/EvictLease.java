package cloud.xuantong.registry.state;

/** Privileged management eviction, still conditional on the current lease fence. */
public record EvictLease(
        RegistryActor actor,
        LeaseReference expectedLease,
        long observedTimeEpochMs) implements RegistryMutation {

    public EvictLease {
        if (actor == null || expectedLease == null) {
            throw new IllegalArgumentException("actor and expectedLease must not be null");
        }
        if (observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("observedTimeEpochMs must not be negative");
        }
    }
}
