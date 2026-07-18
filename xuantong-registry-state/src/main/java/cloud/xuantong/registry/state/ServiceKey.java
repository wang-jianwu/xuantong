package cloud.xuantong.registry.state;

public record ServiceKey(
        String namespace,
        String group,
        String serviceName) implements Comparable<ServiceKey> {

    public ServiceKey {
        namespace = name("namespace", namespace);
        group = name("group", group);
        serviceName = name("serviceName", serviceName);
    }

    public String canonicalName() {
        return namespace + ":" + group + ":" + serviceName;
    }

    @Override
    public int compareTo(ServiceKey other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        if (namespaceOrder != 0) {
            return namespaceOrder;
        }
        int groupOrder = group.compareTo(other.group);
        return groupOrder != 0 ? groupOrder : serviceName.compareTo(other.serviceName);
    }

    static String name(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }
}
