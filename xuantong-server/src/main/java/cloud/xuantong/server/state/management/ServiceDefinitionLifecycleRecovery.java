package cloud.xuantong.server.state.management;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public final class ServiceDefinitionLifecycleRecovery {
    @Inject
    private ServiceDefinitionLifecycleCoordinator coordinator;
    @Inject("${statePlane.registry.lifecycleRecoveryIntervalMs:5000}")
    private long intervalMs;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    @Init(index = 1_800)
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        if (intervalMs < 1_000L) {
            throw new IllegalStateException(
                    "statePlane.registry.lifecycleRecoveryIntervalMs must be at least 1000");
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "service-lifecycle-recovery");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        executor.scheduleWithFixedDelay(
                this::recoverSafely,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }

    @Destroy
    public synchronized void stop() {
        running = false;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void recoverSafely() {
        try {
            coordinator.recoverPending();
        } catch (RuntimeException e) {
            if (!running) {
                return;
            }
            log.debug("Service lifecycle recovery scan failed; it will retry", e);
        }
    }
}
