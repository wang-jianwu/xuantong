package cloud.xuantong.probe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone external availability probe for the Xuantong 2.0 control plane. */
public final class XuantongProbeApplication {
    private XuantongProbeApplication() {
    }

    public static void main(String[] args) throws Exception {
        Mode mode = Mode.parse(args);
        if (mode == Mode.HELP) {
            printUsage();
            return;
        }
        ProbeSettings settings = ProbeSettings.fromEnvironment(System.getenv());
        ControlPlaneProbeRunner runner = new ControlPlaneProbeRunner(settings);
        ProbeMetrics metrics = new ProbeMetrics(settings.profile().label());

        if (mode == Mode.ONCE) {
            ProbeObservation observation = runner.run();
            metrics.record(observation);
            System.out.print(metrics.render());
            if (!observation.successful()) {
                System.err.println("Xuantong control-plane probe failed: "
                        + observation.failureCategory());
                System.exit(1);
            }
            return;
        }

        serve(settings, runner, metrics);
    }

    private static void serve(
            ProbeSettings settings,
            ControlPlaneProbeRunner runner,
            ProbeMetrics metrics) throws Exception {
        try (ScheduledExecutorService scheduler =
                     Executors.newSingleThreadScheduledExecutor(runnable -> {
                         Thread thread = new Thread(
                                 runnable, "xuantong-probe-scheduler");
                         thread.setDaemon(true);
                         return thread;
                     });
             ProbeHttpServer server = new ProbeHttpServer(
                     settings.bindHost(), settings.port(), metrics)) {
            AtomicBoolean closed = new AtomicBoolean();
            Runnable shutdown = () -> {
                if (closed.compareAndSet(false, true)) {
                    scheduler.shutdownNow();
                    server.close();
                }
            };
            Runtime runtime = Runtime.getRuntime();
            Thread shutdownHook = new Thread(shutdown, "xuantong-probe-shutdown");
            boolean shutdownHookRegistered = false;
            try {
                runtime.addShutdownHook(shutdownHook);
                shutdownHookRegistered = true;
                scheduler.scheduleWithFixedDelay(
                        () -> metrics.record(runner.run()),
                        0L,
                        settings.intervalMs(),
                        TimeUnit.MILLISECONDS);
                server.start();
                System.err.println("Xuantong external probe listening on http://"
                        + settings.bindHost() + ':' + settings.port()
                        + " for profile=" + settings.profile().label());
                new CountDownLatch(1).await();
            } finally {
                shutdown.run();
                if (shutdownHookRegistered) {
                    try {
                        runtime.removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // JVM shutdown has started; the hook owns cleanup now.
                    }
                }
            }
        }
    }

    private static void printUsage() {
        System.out.println("Configuration is read from XUANTONG_PROBE_* environment variables.");
    }

    private enum Mode {
        ONCE,
        SERVE,
        HELP;

        static Mode parse(String[] args) {
            String[] actual = args == null ? new String[0] : args;
            if (actual.length == 0) return SERVE;
            if (actual.length != 1) {
                throw new IllegalArgumentException(
                        "Exactly one of --once, --serve or --help is allowed");
            }
            return switch (actual[0]) {
                case "--once" -> ONCE;
                case "--serve" -> SERVE;
                case "--help", "-h" -> HELP;
                default -> throw new IllegalArgumentException(
                        "Unknown probe argument: " + actual[0]);
            };
        }
    }
}
