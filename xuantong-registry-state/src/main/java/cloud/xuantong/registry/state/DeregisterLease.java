package cloud.xuantong.registry.state;

public record DeregisterLease(
        RegistryActor actor,
        LeaseReference lease,
        long observedTimeEpochMs) implements RegistryMutation {

    public DeregisterLease {
        if (actor == null || lease == null) {
            throw new IllegalArgumentException("actor and lease must not be null");
        }
        if (observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("observedTimeEpochMs must not be negative");
        }
    }
}
