package cloud.xuantong.registry.state;

public record RegistryInstance(
        ServiceRegistration registration,
        String leaseId,
        long leaseEpoch,
        long recoveryEpoch,
        long renewSequence,
        String ownerClientInstanceId,
        String ownerApplicationName,
        long registeredAtEpochMs,
        long lastRenewedAtEpochMs,
        long expiresAtEpochMs) {

    public RegistryInstance {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        leaseId = InstanceKey.required("leaseId", leaseId, 256);
        ownerClientInstanceId = InstanceKey.required(
                "ownerClientInstanceId", ownerClientInstanceId, 256);
        ownerApplicationName = InstanceKey.required(
                "ownerApplicationName", ownerApplicationName, 128);
        if (leaseEpoch < 1 || recoveryEpoch < 1 || renewSequence < 0) {
            throw new IllegalArgumentException("lease epochs and sequence are invalid");
        }
        if (registeredAtEpochMs < 0
                || lastRenewedAtEpochMs < registeredAtEpochMs
                || expiresAtEpochMs <= lastRenewedAtEpochMs) {
            throw new IllegalArgumentException("lease timestamps are invalid");
        }
    }

    public InstanceKey instanceKey() {
        return registration.instanceKey();
    }

    public boolean expiredAt(long logicalTimeEpochMs) {
        return expiresAtEpochMs <= logicalTimeEpochMs;
    }
}
