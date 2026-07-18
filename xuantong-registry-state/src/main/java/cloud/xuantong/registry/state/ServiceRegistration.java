package cloud.xuantong.registry.state;

public record ServiceRegistration(
        InstanceKey instanceKey,
        long serviceGeneration,
        String ip,
        int port,
        double weight,
        boolean enabled,
        String metadata) {

    public ServiceRegistration {
        if (instanceKey == null || serviceGeneration < 0) {
            throw new IllegalArgumentException(
                    "instanceKey must be present and serviceGeneration non-negative");
        }
        ip = InstanceKey.required("ip", ip, 128);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (!Double.isFinite(weight) || weight <= 0D) {
            throw new IllegalArgumentException("weight must be finite and positive");
        }
        metadata = metadata == null ? "" : metadata;
        if (metadata.length() > 64 * 1024) {
            throw new IllegalArgumentException("metadata is too long");
        }
    }

    public ServiceRegistration withServiceGeneration(long generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("service generation must be positive");
        }
        return new ServiceRegistration(
                instanceKey, generation, ip, port, weight, enabled, metadata);
    }
}
