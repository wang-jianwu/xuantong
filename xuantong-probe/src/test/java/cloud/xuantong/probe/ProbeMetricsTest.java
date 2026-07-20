package cloud.xuantong.probe;

import cloud.xuantong.client.ControlPlaneProbeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeMetricsTest {
    @Test
    void metricsExposeRequestReplyHealthWithoutSecretsOrUnboundedErrors() {
        ProbeMetrics metrics = new ProbeMetrics("config");
        metrics.record(ProbeObservation.success(
                25_000_000L,
                1_700_000_000_000L,
                new ControlPlaneProbeResult(
                        "gateway-1",
                        "cluster-a",
                        3L,
                        9L,
                        "sd:tcp://gateway-a:8090/control-v2",
                        0,
                        2_000_000L,
                        1_700_000_000_000L,
                        1_700_000_000_004L,
                        1_700_000_000_001L,
                        1_700_000_000_002L)));

        String rendered = metrics.render();

        assertTrue(rendered.contains("xuantong_probe_success{profile=\"config\"} 1.000000000"));
        assertTrue(rendered.contains("xuantong_probe_rpc_duration_seconds"));
        assertTrue(rendered.contains("gateway_id=\"gateway-1\""));
        assertTrue(rendered.contains("address=\"sd:tcp://gateway-a:8090/control-v2\""));
        assertFalse(rendered.contains("token"));
        assertFalse(rendered.contains("password"));
    }

    @Test
    void failedMetricsStayScrapeableAndRemoveStaleGatewayInfo() {
        ProbeMetrics metrics = new ProbeMetrics("discovery");
        metrics.record(ProbeObservation.failure(
                50_000_000L, 1_700_000_000_000L, "timeout"));

        String rendered = metrics.render();

        assertFalse(metrics.healthy());
        assertTrue(rendered.contains("xuantong_probe_success{profile=\"discovery\"} 0.000000000"));
        assertTrue(rendered.contains("xuantong_probe_failure_total{profile=\"discovery\"} 1"));
        assertFalse(rendered.contains("xuantong_probe_info"));
    }
}
