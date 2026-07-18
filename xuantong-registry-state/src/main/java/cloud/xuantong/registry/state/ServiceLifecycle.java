package cloud.xuantong.registry.state;

public record ServiceLifecycle(
        ServiceKey serviceKey,
        long generation,
        ServiceLifecycleStatus status,
        long updatedAtEpochMs) {

    public ServiceLifecycle {
        if (serviceKey == null || generation < 1 || status == null
                || updatedAtEpochMs < 0) {
            throw new IllegalArgumentException("Service lifecycle is invalid");
        }
    }

    public boolean active() {
        return status == ServiceLifecycleStatus.ACTIVE;
    }
}
