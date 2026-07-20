package cloud.xuantong.client.metrics;

import cloud.xuantong.client.model.LeaseRenewalResult;
import cloud.xuantong.client.model.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-memory per-Discovery-Agent metrics for authoritative lease renewals.
 *
 * <p>The histogram observes how much of the previous lease TTL remained when
 * Registry State committed a successful renewal. It uses the server commit
 * clock from the mutation response, not the client wall clock. Labels exclude
 * leaseId and credentials to avoid secret exposure and unbounded churn.</p>
 */
public final class LeaseRenewalMetrics {
    private static final long[] MARGIN_BUCKETS_MS = {
            0L, 1_000L, 2_500L, 5_000L, 10_000L,
            15_000L, 30_000L, 60_000L, 120_000L, 300_000L
    };

    private final String namespace;
    private final String group;
    private final String serviceName;
    private final long[] cumulativeBuckets = new long[MARGIN_BUCKETS_MS.length];
    private String instanceId = "";
    private boolean registered;
    private long successCount;
    private long failureCount;
    private long marginObservationCount;
    private long marginSumMs;
    private long lastMarginMs = -1L;
    private long lastRequestDurationNanos;
    private long leaseEpoch;
    private long renewSequence;
    private long expiresAtEpochMs;

    public LeaseRenewalMetrics(String namespace, String group, String serviceName) {
        this.namespace = requireText("namespace", namespace);
        this.group = requireText("group", group);
        this.serviceName = requireText("serviceName", serviceName);
    }

    public synchronized void registered(ServiceInstance instance) {
        updateLease(instance, true);
    }

    public synchronized void deregistered() {
        registered = false;
        expiresAtEpochMs = 0L;
    }

    public synchronized void renewalSucceeded(
            ServiceInstance previous,
            LeaseRenewalResult renewed,
            long requestDurationNanos) {
        successCount++;
        lastRequestDurationNanos = Math.max(0L, requestDurationNanos);
        Long previousExpiry = previous == null ? null : previous.getExpiresAt();
        if (previousExpiry != null && previousExpiry > 0L) {
            long marginMs = previousExpiry - renewed.serverTimeEpochMs();
            lastMarginMs = marginMs;
            marginObservationCount++;
            // A negative value is retained in lastMargin and the le="0" bucket
            // for alerting, while histogram _sum stays monotonic for rate().
            marginSumMs = saturatingAdd(marginSumMs, Math.max(0L, marginMs));
            for (int index = 0; index < MARGIN_BUCKETS_MS.length; index++) {
                if (marginMs <= MARGIN_BUCKETS_MS[index]) {
                    cumulativeBuckets[index]++;
                }
            }
        }
        updateLease(renewed.instance(), true);
    }

    public synchronized void renewalFailed(long requestDurationNanos) {
        failureCount++;
        lastRequestDurationNanos = Math.max(0L, requestDurationNanos);
    }

    public synchronized LeaseRenewalMetricsSnapshot snapshot() {
        List<LeaseRenewalMetricsSnapshot.Bucket> buckets = new ArrayList<>(
                MARGIN_BUCKETS_MS.length);
        for (int index = 0; index < MARGIN_BUCKETS_MS.length; index++) {
            buckets.add(new LeaseRenewalMetricsSnapshot.Bucket(
                    MARGIN_BUCKETS_MS[index], cumulativeBuckets[index]));
        }
        return new LeaseRenewalMetricsSnapshot(
                namespace,
                group,
                serviceName,
                instanceId,
                registered,
                successCount,
                failureCount,
                marginObservationCount,
                marginSumMs,
                lastMarginMs,
                lastRequestDurationNanos,
                leaseEpoch,
                renewSequence,
                expiresAtEpochMs,
                buckets);
    }

    public synchronized String renderPrometheus() {
        LeaseRenewalMetricsSnapshot value = snapshot();
        String labels = labels(value);
        StringBuilder output = new StringBuilder(2_048);
        counter(output, "xuantong_discovery_lease_renewal_success_total",
                "Successful authoritative lease renewals.", labels, value.successCount());
        counter(output, "xuantong_discovery_lease_renewal_failure_total",
                "Failed authoritative lease renewal attempts.", labels, value.failureCount());
        gauge(output, "xuantong_discovery_lease_registered",
                "Whether this Discovery Agent currently owns a local registration.",
                labels, value.registered() ? 1D : 0D);
        gauge(output, "xuantong_discovery_lease_renewal_last_margin_seconds",
                "Previous lease TTL remaining at the last successful renewal commit.",
                labels, value.lastMarginMs() < 0L ? Double.NaN : value.lastMarginMs() / 1_000D);
        gauge(output, "xuantong_discovery_lease_renewal_last_duration_seconds",
                "Client Request/Reply duration of the last lease renewal attempt.",
                labels, value.lastRequestDurationNanos()
                        / (double) TimeUnit.SECONDS.toNanos(1L));
        gauge(output, "xuantong_discovery_lease_expires_timestamp_seconds",
                "Authoritative expiry timestamp of the current local lease.",
                labels, value.expiresAtEpochMs() / 1_000D);
        gauge(output, "xuantong_discovery_lease_epoch",
                "Authoritative fencing epoch of the current local lease.",
                labels, value.leaseEpoch());
        gauge(output, "xuantong_discovery_lease_renew_sequence",
                "Authoritative renewal sequence of the current local lease.",
                labels, value.renewSequence());

        output.append("# HELP xuantong_discovery_lease_renewal_margin_seconds Previous lease TTL remaining when Registry State committed a successful renewal.\n")
                .append("# TYPE xuantong_discovery_lease_renewal_margin_seconds histogram\n");
        for (LeaseRenewalMetricsSnapshot.Bucket bucket : value.marginBuckets()) {
            output.append("xuantong_discovery_lease_renewal_margin_seconds_bucket")
                    .append(labelsWithLe(value, bucket.upperBoundMs() / 1_000D))
                    .append(' ').append(bucket.cumulativeCount()).append('\n');
        }
        output.append("xuantong_discovery_lease_renewal_margin_seconds_bucket")
                .append(labelsWithLe(value, Double.POSITIVE_INFINITY))
                .append(' ').append(value.marginObservationCount()).append('\n')
                .append("xuantong_discovery_lease_renewal_margin_seconds_sum")
                .append(labels).append(' ')
                .append(format(value.marginSumMs() / 1_000D)).append('\n')
                .append("xuantong_discovery_lease_renewal_margin_seconds_count")
                .append(labels).append(' ')
                .append(value.marginObservationCount()).append('\n');
        return output.toString();
    }

    private void updateLease(ServiceInstance instance, boolean active) {
        if (instance == null) {
            return;
        }
        instanceId = instance.getInstanceId() == null ? "" : instance.getInstanceId();
        registered = active;
        leaseEpoch = value(instance.getLeaseEpoch());
        renewSequence = value(instance.getRenewSequence());
        expiresAtEpochMs = value(instance.getExpiresAt());
    }

    private String labels(LeaseRenewalMetricsSnapshot value) {
        return "{namespace=\"" + escape(value.namespace())
                + "\",group=\"" + escape(value.group())
                + "\",service=\"" + escape(value.serviceName())
                + "\",client_instance_id=\"" + escape(value.instanceId()) + "\"}";
    }

    private String labelsWithLe(LeaseRenewalMetricsSnapshot value, double upperBoundSeconds) {
        String base = labels(value);
        String le = Double.isInfinite(upperBoundSeconds)
                ? "+Inf" : format(upperBoundSeconds);
        return base.substring(0, base.length() - 1)
                + ",le=\"" + le + "\"}";
    }

    private static void gauge(
            StringBuilder output,
            String name,
            String help,
            String labels,
            double value) {
        metric(output, name, help, "gauge", labels,
                Double.isNaN(value) ? "NaN" : format(value));
    }

    private static void counter(
            StringBuilder output,
            String name,
            String help,
            String labels,
            long value) {
        metric(output, name, help, "counter", labels, Long.toString(value));
    }

    private static void metric(
            StringBuilder output,
            String name,
            String help,
            String type,
            String labels,
            String value) {
        output.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(labels).append(' ').append(value).append('\n');
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
