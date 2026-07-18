package cloud.xuantong.registry.state;

public record RegistryOverview(
        long registryRevision,
        long serverTimeEpochMs,
        long activeInstanceCount,
        long activeServiceCount) {

    public RegistryOverview {
        if (registryRevision < 0 || serverTimeEpochMs < 0
                || activeInstanceCount < 0 || activeServiceCount < 0) {
            throw new IllegalArgumentException("Registry overview values must not be negative");
        }
    }
}
