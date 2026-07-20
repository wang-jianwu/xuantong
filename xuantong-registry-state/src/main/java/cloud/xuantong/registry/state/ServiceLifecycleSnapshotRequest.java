package cloud.xuantong.registry.state;

/** Bounded page request for Registry lifecycle restore validation. */
public record ServiceLifecycleSnapshotRequest(
        ServiceKey afterExclusive,
        int limit) {

    public ServiceLifecycleSnapshotRequest {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
    }
}
