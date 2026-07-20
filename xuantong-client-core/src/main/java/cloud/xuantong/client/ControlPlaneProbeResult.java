package cloud.xuantong.client;

import java.util.concurrent.TimeUnit;

/**
 * Result of one successful Xuantong control-plane Request/Reply probe.
 *
 * <p>The address never contains credentials: Xuantong 2.0 carries the access
 * token in the Hello payload rather than in the Socket.D URL.</p>
 */
public record ControlPlaneProbeResult(
        String gatewayId,
        String clusterId,
        long transportGeneration,
        long connectionGeneration,
        String address,
        int addressIndex,
        long rpcDurationNanos,
        long clientSendEpochMs,
        long clientReceiveEpochMs,
        long serverReceiveEpochMs,
        long serverSendEpochMs) {

    public ControlPlaneProbeResult {
        gatewayId = requireText("gatewayId", gatewayId);
        clusterId = requireText("clusterId", clusterId);
        address = requireText("address", address);
        if (transportGeneration <= 0 || connectionGeneration <= 0) {
            throw new IllegalArgumentException(
                    "transportGeneration and connectionGeneration must be positive");
        }
        if (addressIndex < 0 || rpcDurationNanos < 0
                || clientSendEpochMs <= 0 || clientReceiveEpochMs <= 0
                || serverReceiveEpochMs <= 0 || serverSendEpochMs <= 0) {
            throw new IllegalArgumentException("probe timing or address index is invalid");
        }
    }

    public double rpcDurationSeconds() {
        return rpcDurationNanos / (double) TimeUnit.SECONDS.toNanos(1L);
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
