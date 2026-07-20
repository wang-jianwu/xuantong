package cloud.xuantong.client.metrics;

/**
 * Bounded runtime counters for one control-plane transport.
 *
 * <p>{@code inFlightRequests} counts application request waits owned by Xuantong.
 * Socket.D 2.6.0 does not expose the size of its internal RequestStream manager,
 * so this value must be combined with request accepted/completed equality and
 * Session reclamation when diagnosing stream leaks.</p>
 */
public record ControlPlaneTransportMetricsSnapshot(
        int activeSessions,
        int inFlightRequests,
        int registeredWatches,
        int activeSubscribeStreams,
        boolean closed) {

    public ControlPlaneTransportMetricsSnapshot {
        requireNonNegative("activeSessions", activeSessions);
        requireNonNegative("inFlightRequests", inFlightRequests);
        requireNonNegative("registeredWatches", registeredWatches);
        requireNonNegative("activeSubscribeStreams", activeSubscribeStreams);
        if (activeSubscribeStreams > registeredWatches) {
            throw new IllegalArgumentException(
                    "activeSubscribeStreams must not exceed registeredWatches");
        }
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
