package cloud.xuantong.client.model;

public record ServiceInvalidation(
        long registryRevision,
        String eventType,
        String instanceId,
        ServiceInstance instance) {

    public ServiceInvalidation {
        if (registryRevision < 1) {
            throw new IllegalArgumentException("registryRevision must be positive");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }
}
