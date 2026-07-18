package cloud.xuantong.registry.state;

import java.util.List;

public record RegistryMutationResult(
        String action,
        long registryRevision,
        long serverTimeEpochMs,
        List<ServiceLifecycle> services,
        List<RegistryInstance> instances,
        List<InstanceKey> removedInstances) {

    public RegistryMutationResult {
        action = InstanceKey.required("action", action, 64);
        if (registryRevision < 0 || serverTimeEpochMs < 0) {
            throw new IllegalArgumentException("Registry result watermarks are invalid");
        }
        services = List.copyOf(services == null ? List.of() : services);
        instances = List.copyOf(instances == null ? List.of() : instances);
        removedInstances = List.copyOf(
                removedInstances == null ? List.of() : removedInstances);
    }
}
