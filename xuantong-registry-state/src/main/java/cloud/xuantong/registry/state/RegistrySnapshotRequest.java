package cloud.xuantong.registry.state;

import java.util.List;

public record RegistrySnapshotRequest(
        String namespace,
        String group,
        List<String> serviceNames) {

    public RegistrySnapshotRequest {
        namespace = ServiceKey.name("namespace", namespace);
        group = ServiceKey.name("group", group);
        serviceNames = List.copyOf(serviceNames == null ? List.of() : serviceNames);
        for (String serviceName : serviceNames) {
            ServiceKey.name("serviceName", serviceName);
        }
        if (serviceNames.stream().distinct().count() != serviceNames.size()) {
            throw new IllegalArgumentException("serviceNames must not contain duplicates");
        }
    }

    public boolean matches(InstanceKey key) {
        return namespace.equals(key.service().namespace())
                && group.equals(key.service().group())
                && (serviceNames.isEmpty()
                || serviceNames.contains(key.service().serviceName()));
    }
}
