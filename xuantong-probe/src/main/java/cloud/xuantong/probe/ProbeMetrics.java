package cloud.xuantong.probe;

import cloud.xuantong.client.ControlPlaneProbeResult;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class ProbeMetrics {
    private final String profile;
    private long total;
    private long failures;
    private ProbeObservation latest = ProbeObservation.failure(0L, 0L, "not_run");

    ProbeMetrics(String profile) {
        this.profile = profile;
    }

    synchronized void record(ProbeObservation observation) {
        latest = observation;
        total++;
        if (!observation.successful()) {
            failures++;
        }
    }

    synchronized boolean healthy() {
        return latest.successful();
    }

    synchronized String failureCategory() {
        return latest.failureCategory();
    }

    synchronized String render() {
        String labels = "{profile=\"" + escape(profile) + "\"}";
        StringBuilder output = new StringBuilder(1_536);
        gauge(output, "xuantong_probe_success",
                "Whether the last external Hello and Probe exchange succeeded.",
                labels, latest.successful() ? 1D : 0D);
        gauge(output, "xuantong_probe_duration_seconds",
                "Duration of the last full DNS/TCP/TLS, Hello and Probe attempt.",
                labels, seconds(latest.durationNanos()));
        counter(output, "xuantong_probe_total",
                "Total external control-plane probe attempts.", labels, total);
        counter(output, "xuantong_probe_failure_total",
                "Total failed external control-plane probe attempts.", labels, failures);
        gauge(output, "xuantong_probe_last_completed_timestamp_seconds",
                "Unix timestamp when the last external probe completed.",
                labels, latest.completedAtEpochMs() / 1_000D);

        ControlPlaneProbeResult result = latest.result();
        if (latest.successful() && result != null) {
            gauge(output, "xuantong_probe_rpc_duration_seconds",
                    "Duration of the successful system/probe Request/Reply exchange.",
                    labels, result.rpcDurationSeconds());
            gauge(output, "xuantong_probe_transport_generation",
                    "Transport compatibility generation returned by Hello.",
                    labels, result.transportGeneration());
            gauge(output, "xuantong_probe_connection_generation",
                    "Physical connection generation validated by Probe.",
                    labels, result.connectionGeneration());
            String infoLabels = "{profile=\"" + escape(profile)
                    + "\",gateway_id=\"" + escape(result.gatewayId())
                    + "\",cluster_id=\"" + escape(result.clusterId())
                    + "\",address=\"" + escape(result.address()) + "\"}";
            gauge(output, "xuantong_probe_info",
                    "Identity of the Gateway selected by the last successful probe.",
                    infoLabels, 1D);
        }
        return output.toString();
    }

    private static void gauge(
            StringBuilder output,
            String name,
            String help,
            String labels,
            double value) {
        metric(output, name, help, "gauge", labels, format(value));
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

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static double seconds(long nanos) {
        return nanos / (double) TimeUnit.SECONDS.toNanos(1L);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }
}
