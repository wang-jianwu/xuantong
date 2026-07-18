package cloud.xuantong.registry.state;

public record RegistryChangeEvent(
        long registryRevision,
        String eventType,
        InstanceKey instanceKey,
        RegistryInstance instance) {

    public RegistryChangeEvent {
        if (registryRevision < 1 || instanceKey == null) {
            throw new IllegalArgumentException("Registry change revision and key are required");
        }
        eventType = InstanceKey.required("eventType", eventType, 64);
        if (instance != null && !instanceKey.equals(instance.instanceKey())) {
            throw new IllegalArgumentException("Registry change instance key does not match");
        }
    }
}
