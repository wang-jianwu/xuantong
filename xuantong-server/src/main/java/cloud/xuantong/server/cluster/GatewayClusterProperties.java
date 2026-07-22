package cloud.xuantong.server.cluster;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

@Configuration
public class GatewayClusterProperties {
    @Inject("${xuantong.deployment:standalone}")
    private String deployment;
    @Inject("${controlPlane.cluster.snapshotIntervalMs:2000}")
    private long snapshotIntervalMs;
    @Inject("${controlPlane.cluster.leaseTtlMs:10000}")
    private long leaseTtlMs;
    @Inject("${controlPlane.cluster.staleRetentionMs:60000}")
    private long staleRetentionMs;
    @Inject("${controlPlane.cluster.maxConnectionDetails:5000}")
    private int maxConnectionDetails;
    @Inject("${controlPlane.cluster.quotaSafetyReservePercent:5}")
    private int quotaSafetyReservePercent;
    @Inject("${controlPlane.cluster.revocationPollIntervalMs:500}")
    private long revocationPollIntervalMs;
    @Inject("${controlPlane.cluster.revocationBatchSize:500}")
    private int revocationBatchSize;
    @Inject("${controlPlane.cluster.revocationRetentionMs:604800000}")
    private long revocationRetentionMs;
    @Inject("${controlPlane.cluster.cleanupIntervalMs:3600000}")
    private long cleanupIntervalMs;

    public GatewayClusterProperties() {
    }

    GatewayClusterProperties(
            boolean coordinationEnabled,
            long snapshotIntervalMs,
            long leaseTtlMs,
            long staleRetentionMs,
            int maxConnectionDetails,
            int quotaSafetyReservePercent,
            long revocationPollIntervalMs,
            int revocationBatchSize,
            long revocationRetentionMs,
            long cleanupIntervalMs) {
        this.deployment = coordinationEnabled ? "cluster" : "standalone";
        this.snapshotIntervalMs = snapshotIntervalMs;
        this.leaseTtlMs = leaseTtlMs;
        this.staleRetentionMs = staleRetentionMs;
        this.maxConnectionDetails = maxConnectionDetails;
        this.quotaSafetyReservePercent = quotaSafetyReservePercent;
        this.revocationPollIntervalMs = revocationPollIntervalMs;
        this.revocationBatchSize = revocationBatchSize;
        this.revocationRetentionMs = revocationRetentionMs;
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public boolean isCoordinationEnabled() {
        if (deployment == null || deployment.isBlank()
                || "standalone".equalsIgnoreCase(deployment)) {
            return false;
        }
        if ("cluster".equalsIgnoreCase(deployment)) {
            return true;
        }
        throw new IllegalStateException(
                "xuantong.deployment must be standalone or cluster");
    }

    public long snapshotIntervalMs() {
        if (snapshotIntervalMs < 250L || snapshotIntervalMs > 60_000L) {
            throw new IllegalStateException(
                    "controlPlane.cluster.snapshotIntervalMs must be between 250 and 60000");
        }
        return snapshotIntervalMs;
    }

    public long leaseTtlMs() {
        long interval = snapshotIntervalMs();
        if (leaseTtlMs < interval * 3L || leaseTtlMs > 300_000L) {
            throw new IllegalStateException(
                    "controlPlane.cluster.leaseTtlMs must be at least three snapshot intervals "
                            + "and no more than 300000");
        }
        return leaseTtlMs;
    }

    public long staleRetentionMs() {
        long ttl = leaseTtlMs();
        if (staleRetentionMs < ttl || staleRetentionMs > 86_400_000L) {
            throw new IllegalStateException(
                    "controlPlane.cluster.staleRetentionMs must be between leaseTtlMs and 86400000");
        }
        return staleRetentionMs;
    }

    public int maxConnectionDetails() {
        if (maxConnectionDetails < 0 || maxConnectionDetails > 20_000) {
            throw new IllegalStateException(
                    "controlPlane.cluster.maxConnectionDetails must be between 0 and 20000");
        }
        return maxConnectionDetails;
    }

    public int quotaSafetyReservePercent() {
        if (quotaSafetyReservePercent < 0 || quotaSafetyReservePercent > 50) {
            throw new IllegalStateException(
                    "controlPlane.cluster.quotaSafetyReservePercent must be between 0 and 50");
        }
        return quotaSafetyReservePercent;
    }

    public long revocationPollIntervalMs() {
        if (revocationPollIntervalMs < 100L || revocationPollIntervalMs > 60_000L) {
            throw new IllegalStateException(
                    "controlPlane.cluster.revocationPollIntervalMs must be between 100 and 60000");
        }
        return revocationPollIntervalMs;
    }

    public int revocationBatchSize() {
        if (revocationBatchSize < 1 || revocationBatchSize > 5_000) {
            throw new IllegalStateException(
                    "controlPlane.cluster.revocationBatchSize must be between 1 and 5000");
        }
        return revocationBatchSize;
    }

    public long revocationRetentionMs() {
        if (revocationRetentionMs < leaseTtlMs() || revocationRetentionMs > 2_592_000_000L) {
            throw new IllegalStateException(
                    "controlPlane.cluster.revocationRetentionMs must be between leaseTtlMs "
                            + "and 2592000000");
        }
        return revocationRetentionMs;
    }

    public long cleanupIntervalMs() {
        if (cleanupIntervalMs < 60_000L || cleanupIntervalMs > 86_400_000L) {
            throw new IllegalStateException(
                    "controlPlane.cluster.cleanupIntervalMs must be between 60000 and 86400000");
        }
        return cleanupIntervalMs;
    }
}
