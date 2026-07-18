package cloud.xuantong.registry.state;

public record InstanceKey(ServiceKey service, String instanceId)
        implements Comparable<InstanceKey> {

    public InstanceKey {
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        instanceId = required("instanceId", instanceId, 256);
    }

    public String canonicalName() {
        return service.canonicalName() + ":" + instanceId;
    }

    @Override
    public int compareTo(InstanceKey other) {
        int serviceOrder = service.compareTo(other.service);
        return serviceOrder != 0 ? serviceOrder : instanceId.compareTo(other.instanceId);
    }

    static String required(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
