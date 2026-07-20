package cloud.xuantong.gateway.socketd;

import cloud.xuantong.common.metrics.FixedLatencyHistogram;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewayMetricsTest {
    @Test
    void recordsRequestAndWatchAcknowledgementHistograms() {
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(
                new ControlPlaneGatewayProperties(
                        "metrics-cluster", "metrics-gateway", 1L, 10_000L));

        assertEquals(
                ControlPlaneGatewayRuntime.Admission.ACCEPTED,
                runtime.tryAcquireRequest());
        runtime.releaseRequest(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(6));
        runtime.watchReplyAwaitingAcknowledgement();
        runtime.watchAcknowledged(125L);

        FixedLatencyHistogram.Snapshot request = runtime.requestLatencySnapshot();
        assertEquals(1L, request.count());
        assertTrue(request.totalSeconds() >= 0.006D);
        assertEquals(0L, bucket(request, 5L));
        assertEquals(1L, bucket(request, 10L));

        FixedLatencyHistogram.Snapshot watch = runtime.watchAckLatencySnapshot();
        assertEquals(1L, watch.count());
        assertEquals(0L, bucket(watch, 100L));
        assertEquals(1L, bucket(watch, 250L));
    }

    private long bucket(FixedLatencyHistogram.Snapshot snapshot, long boundMillis) {
        long[] bounds = snapshot.upperBoundsMillis();
        long[] counts = snapshot.cumulativeCounts();
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i] == boundMillis) {
                return counts[i];
            }
        }
        throw new AssertionError("Missing bucket " + boundMillis);
    }
}
