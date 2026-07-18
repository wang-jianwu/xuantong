package cloud.xuantong.registry.state;

public record RegistryActor(
        String tenant,
        String clientInstanceId,
        String applicationName) {

    public RegistryActor {
        tenant = required("tenant", tenant, 128);
        clientInstanceId = required("clientInstanceId", clientInstanceId, 256);
        applicationName = required("applicationName", applicationName, 128);
    }

    public static RegistryActor system(String nodeId) {
        return new RegistryActor("system", "state-node:" + nodeId, "xuantong-state");
    }

    public String idempotencyScope() {
        return tenant + "\u0000" + clientInstanceId;
    }

    private static String required(String field, String value, int maxLength) {
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
