package cloud.xuantong.client.metrics;

import java.util.List;

/** Immutable per-Discovery-Agent lease renewal telemetry snapshot. */
public record LeaseRenewalMetricsSnapshot(
        String namespace,
        String group,
        String serviceName,
        String instanceId,
        boolean registered,
        long successCount,
        long failureCount,
        long marginObservationCount,
        long marginSumMs,
        long lastMarginMs,
        long lastRequestDurationNanos,
        long leaseEpoch,
        long renewSequence,
        long expiresAtEpochMs,
        List<Bucket> marginBuckets) {

    public LeaseRenewalMetricsSnapshot {
        marginBuckets = List.copyOf(marginBuckets == null ? List.of() : marginBuckets);
    }

    public record Bucket(long upperBoundMs, long cumulativeCount) {
    }
}
