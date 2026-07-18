package cloud.xuantong.registry.state;

public record ActivateServiceDefinition(
        RegistryActor actor,
        ServiceKey serviceKey,
        long expectedPreviousGeneration,
        long observedTimeEpochMs) implements RegistryMutation {

    public ActivateServiceDefinition {
        if (actor == null || serviceKey == null || expectedPreviousGeneration < 0
                || observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("Service activation is invalid");
        }
    }
}
