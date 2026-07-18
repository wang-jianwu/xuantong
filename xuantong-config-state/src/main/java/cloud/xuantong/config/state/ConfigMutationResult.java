package cloud.xuantong.config.state;

/** Logical mutation result retained for operationId replay. */
public record ConfigMutationResult(
        ReleaseDecision decision,
        long createdContentRevision,
        long eventRevision) {

    public ConfigMutationResult {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (createdContentRevision < 0) {
            throw new IllegalArgumentException(
                    "createdContentRevision must not be negative");
        }
        if (eventRevision < 1) {
            throw new IllegalArgumentException("eventRevision must be positive");
        }
    }
}
