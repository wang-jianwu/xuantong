package cloud.xuantong.config.state;

/** Bounded page request for the restore-validation projection digest. */
public record ConfigProjectionSnapshotRequest(
        ConfigKey afterExclusive,
        int limit) {

    public ConfigProjectionSnapshotRequest {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
    }
}
