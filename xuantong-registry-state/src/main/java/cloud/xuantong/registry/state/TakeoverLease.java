package cloud.xuantong.registry.state;

public record TakeoverLease(
        RegistryActor actor,
        LeaseReference expectedLease,
        String proposedLeaseId,
        long ttlMs,
        long observedTimeEpochMs) implements RegistryMutation {

    public TakeoverLease {
        if (actor == null || expectedLease == null) {
            throw new IllegalArgumentException("actor and expectedLease must not be null");
        }
        proposedLeaseId = InstanceKey.required("proposedLeaseId", proposedLeaseId, 256);
        if (ttlMs < 1 || observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("ttlMs and observedTimeEpochMs are invalid");
        }
    }
}
