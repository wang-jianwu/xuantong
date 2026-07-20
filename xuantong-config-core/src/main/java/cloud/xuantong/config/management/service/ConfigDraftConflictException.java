package cloud.xuantong.config.management.service;

import cloud.xuantong.config.management.model.ConfigResource;

public class ConfigDraftConflictException extends IllegalStateException {
    private final long expectedDraftRevision;
    private final long actualDraftRevision;
    private final ConfigResource current;
    private final ConfigResource submitted;

    public ConfigDraftConflictException(
            long expectedDraftRevision,
            long actualDraftRevision,
            ConfigResource current,
            ConfigResource submitted) {
        super("Config draft was modified concurrently: expected draftRevision="
                + expectedDraftRevision + ", actual=" + actualDraftRevision);
        this.expectedDraftRevision = expectedDraftRevision;
        this.actualDraftRevision = actualDraftRevision;
        this.current = current;
        this.submitted = submitted;
    }

    public long expectedDraftRevision() {
        return expectedDraftRevision;
    }

    public long actualDraftRevision() {
        return actualDraftRevision;
    }

    public ConfigResource current() {
        return current;
    }

    public ConfigResource submitted() {
        return submitted;
    }
}
