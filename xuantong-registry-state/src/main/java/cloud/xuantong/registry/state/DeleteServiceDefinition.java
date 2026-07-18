package cloud.xuantong.registry.state;

public record DeleteServiceDefinition(
        RegistryActor actor,
        ServiceKey serviceKey,
        long expectedGeneration,
        long observedTimeEpochMs) implements RegistryMutation {

    public DeleteServiceDefinition {
        if (actor == null || serviceKey == null || expectedGeneration < 1
                || observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("Service deletion is invalid");
        }
    }
}
