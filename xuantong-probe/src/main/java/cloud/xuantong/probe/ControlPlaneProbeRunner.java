package cloud.xuantong.probe;

import cloud.xuantong.client.ControlPlaneProbeResult;
import cloud.xuantong.client.transport.impl.SocketDTransport;

import java.util.Locale;

final class ControlPlaneProbeRunner {
    private final ProbeSettings settings;

    ControlPlaneProbeRunner(ProbeSettings settings) {
        this.settings = settings;
    }

    ProbeObservation run() {
        long startedAt = System.nanoTime();
        try (SocketDTransport transport = newTransport()) {
            ControlPlaneProbeResult result = transport.probeOnce(
                    settings.servers(),
                    settings.namespace(),
                    settings.group(),
                    settings.accessToken());
            return ProbeObservation.success(
                    System.nanoTime() - startedAt,
                    System.currentTimeMillis(),
                    result);
        } catch (Exception error) {
            return ProbeObservation.failure(
                    System.nanoTime() - startedAt,
                    System.currentTimeMillis(),
                    failureCategory(error));
        }
    }

    private SocketDTransport newTransport() {
        if (settings.profile() == ProbeSettings.Profile.DISCOVERY) {
            return SocketDTransport.forDiscovery(
                    settings.identity(), settings.controlPlane());
        }
        return new SocketDTransport(settings.identity(), settings.controlPlane());
    }

    static String failureCategory(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        String type = root == null ? "unknown" : root.getClass().getSimpleName();
        String normalized = type.toLowerCase(Locale.ROOT);
        if (normalized.contains("timeout")) return "timeout";
        if (normalized.contains("ssl") || normalized.contains("certificate")
                || normalized.contains("trust") || normalized.contains("key")) {
            return "tls";
        }
        if (normalized.contains("connection") || normalized.contains("channel")
                || normalized.contains("connect")) {
            return "connection";
        }
        if (normalized.contains("protocol") || normalized.contains("protobuf")) {
            return "protocol";
        }
        if (normalized.contains("capability")) return "capability";
        return "unknown";
    }
}
