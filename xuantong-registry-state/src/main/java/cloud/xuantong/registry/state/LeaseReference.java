package cloud.xuantong.registry.state;

public record LeaseReference(
        InstanceKey instanceKey,
        String leaseId,
        long leaseEpoch,
        long recoveryEpoch) {

    public LeaseReference {
        if (instanceKey == null) {
            throw new IllegalArgumentException("instanceKey must not be null");
        }
        leaseId = InstanceKey.required("leaseId", leaseId, 256);
        if (leaseEpoch < 1 || recoveryEpoch < 1) {
            throw new IllegalArgumentException("leaseEpoch and recoveryEpoch must be positive");
        }
    }
}
