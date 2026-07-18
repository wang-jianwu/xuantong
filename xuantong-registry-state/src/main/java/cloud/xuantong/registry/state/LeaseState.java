package cloud.xuantong.registry.state;

public record LeaseState(
        boolean found,
        long registryRevision,
        long serverTimeEpochMs,
        RegistryInstance instance) {

    public LeaseState {
        if (registryRevision < 0 || serverTimeEpochMs < 0) {
            throw new IllegalArgumentException("Lease state watermarks are invalid");
        }
        if (found != (instance != null)) {
            throw new IllegalArgumentException("found must match instance presence");
        }
    }
}
