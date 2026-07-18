package cloud.xuantong.config.state;

/** Durable invalidation entry retained independently from the Raft log. */
public record ConfigChangeEvent(
        long eventRevision,
        ConfigKey configKey,
        long decisionRevision) {

    public ConfigChangeEvent {
        if (eventRevision < 1) {
            throw new IllegalArgumentException("eventRevision must be positive");
        }
        if (configKey == null) {
            throw new IllegalArgumentException("configKey must not be null");
        }
        if (decisionRevision < 1) {
            throw new IllegalArgumentException("decisionRevision must be positive");
        }
    }
}
