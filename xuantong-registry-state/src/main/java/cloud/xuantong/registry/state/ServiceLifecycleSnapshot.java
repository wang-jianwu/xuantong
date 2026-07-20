package cloud.xuantong.registry.state;

import java.util.List;

/** Linearizable Registry lifecycle view used after disaster recovery. */
public record ServiceLifecycleSnapshot(
        long registryRevision,
        long serverTimeEpochMs,
        List<ServiceLifecycle> services,
        boolean hasMore) {

    public ServiceLifecycleSnapshot {
        if (registryRevision < 0 || serverTimeEpochMs < 0) {
            throw new IllegalArgumentException("Registry lifecycle watermarks are invalid");
        }
        services = List.copyOf(services == null ? List.of() : services);
        List<ServiceLifecycle> sorted = services.stream()
                .sorted(java.util.Comparator.comparing(ServiceLifecycle::serviceKey))
                .toList();
        if (!services.equals(sorted)) {
            throw new IllegalArgumentException(
                    "Registry lifecycles must be sorted by service key");
        }
    }
}
