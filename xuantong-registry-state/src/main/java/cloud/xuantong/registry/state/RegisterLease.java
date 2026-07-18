package cloud.xuantong.registry.state;

public record RegisterLease(
        RegistryActor actor,
        ServiceRegistration registration,
        String proposedLeaseId,
        long ttlMs,
        long observedTimeEpochMs) implements RegistryMutation {

    public RegisterLease {
        if (actor == null || registration == null) {
            throw new IllegalArgumentException("actor and registration must not be null");
        }
        proposedLeaseId = InstanceKey.required("proposedLeaseId", proposedLeaseId, 256);
        if (ttlMs < 1 || observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("ttlMs and observedTimeEpochMs are invalid");
        }
    }
}
