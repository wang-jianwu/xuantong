package cloud.xuantong.client.model;

/** One resumable Config State invalidation. */
public record ConfigInvalidation(
        String dataId,
        long eventRevision,
        long decisionRevision) {
}
