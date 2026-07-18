package cloud.xuantong.client;

/**
 * Immutable routing contract for the Xuantong 2.0 control plane.
 *
 * <p>The cluster id and transport generation may be left empty/zero for the first
 * successful Hello. The client then pins the values returned by that Gateway so
 * automatic failover cannot cross a cluster or transport compatibility boundary.</p>
 */
public record ControlPlaneOptions(
        String tenant,
        String stateGroupId,
        String clusterId,
        long transportGeneration,
        String transportPool,
        long connectTimeoutMs,
        long requestTimeoutMs,
        long operationTimeoutMs,
        long closingTimeoutMs) {

    public ControlPlaneOptions {
        tenant = requireText("tenant", tenant);
        stateGroupId = requireText("stateGroupId", stateGroupId);
        clusterId = clusterId == null ? "" : clusterId.trim();
        transportPool = requireText("transportPool", transportPool);
        if (transportGeneration < 0) {
            throw new IllegalArgumentException("transportGeneration must not be negative");
        }
        if (connectTimeoutMs < 100 || requestTimeoutMs < 100
                || operationTimeoutMs < 100 || closingTimeoutMs < 0) {
            throw new IllegalArgumentException("control-plane timeouts are invalid");
        }
        if (operationTimeoutMs < requestTimeoutMs) {
            throw new IllegalArgumentException(
                    "operationTimeoutMs must be greater than or equal to requestTimeoutMs");
        }
    }

    public static ControlPlaneOptions defaults() {
        return new ControlPlaneOptions(
                "default",
                "config-default",
                "",
                0L,
                "tcp-default",
                3_000L,
                3_000L,
                6_000L,
                3_000L);
    }

    public static ControlPlaneOptions registryDefaults() {
        ControlPlaneOptions defaults = defaults();
        return new ControlPlaneOptions(
                defaults.tenant(),
                "registry-default",
                defaults.clusterId(),
                defaults.transportGeneration(),
                defaults.transportPool(),
                defaults.connectTimeoutMs(),
                defaults.requestTimeoutMs(),
                defaults.operationTimeoutMs(),
                defaults.closingTimeoutMs());
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
