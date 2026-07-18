package cloud.xuantong.registry.state;

import java.util.List;

public record RegistrySnapshot(
        long registryRevision,
        long compactionRevision,
        long serverTimeEpochMs,
        List<RegistryInstance> instances) {

    public RegistrySnapshot {
        if (registryRevision < 0 || compactionRevision < 0
                || compactionRevision > registryRevision || serverTimeEpochMs < 0) {
            throw new IllegalArgumentException("Registry snapshot watermarks are invalid");
        }
        instances = List.copyOf(instances == null ? List.of() : instances);
    }
}
