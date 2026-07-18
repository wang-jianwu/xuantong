package cloud.xuantong.registry.state;

public record ExpireLeaseBatch(
        RegistryActor actor,
        int maxExpirations,
        long observedTimeEpochMs) implements RegistryMutation {

    public ExpireLeaseBatch {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (maxExpirations < 1 || observedTimeEpochMs < 0) {
            throw new IllegalArgumentException(
                    "maxExpirations must be positive and observedTimeEpochMs non-negative");
        }
    }
}
