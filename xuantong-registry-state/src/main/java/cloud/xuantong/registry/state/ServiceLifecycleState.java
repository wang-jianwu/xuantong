package cloud.xuantong.registry.state;

public record ServiceLifecycleState(
        boolean found,
        long registryRevision,
        long serverTimeEpochMs,
        ServiceLifecycle lifecycle) {

    public ServiceLifecycleState {
        if (registryRevision < 0 || serverTimeEpochMs < 0
                || found != (lifecycle != null)) {
            throw new IllegalArgumentException("Service lifecycle state is invalid");
        }
    }
}
