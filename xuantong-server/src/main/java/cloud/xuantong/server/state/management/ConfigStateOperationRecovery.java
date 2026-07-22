package cloud.xuantong.server.state.management;

import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.repository.ConfigStateOperationRepository;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Repairs ambiguous commits and lagging SQL projections on every Server node. */
@Slf4j
@Component
public final class ConfigStateOperationRecovery {
    private static final int BATCH_SIZE = 100;
    private static final long MAX_IDLE_INTERVAL_MS = 30_000L;

    @Inject
    private ControlStatePlaneRuntime runtime;
    @Inject
    private ConfigStateOperationRepository operationRepository;
    @Inject
    private ConfigStateManagementService managementService;
    @Inject("${statePlane.config.operationRecoveryIntervalMs:1000}")
    private long recoveryIntervalMs;

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile long nextDelayMs;

    @Init(index = 1_700)
    public void start() {
        if (!runtime.isRunning()) {
            return;
        }
        if (recoveryIntervalMs < 250) {
            throw new IllegalArgumentException(
                    "statePlane.config.operationRecoveryIntervalMs must be at least 250");
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-config-state-recovery");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        nextDelayMs = recoveryIntervalMs;
        scheduleNext(0L);
        log.info("Config State operation recovery started: intervalMs={}", recoveryIntervalMs);
    }

    int recoverOnce() {
        List<ConfigStateOperation> operations = operationRepository
                .findRecoverable(BATCH_SIZE);
        for (ConfigStateOperation operation : operations) {
            try {
                managementService.recover(operation);
            } catch (ConfigStateWriteException e) {
                if (!e.commitUnknown()) {
                    log.warn("Config State operation recovery reached a definitive failure: operationId={}, error={}",
                            operation.getOperationId(), e.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("Config State operation recovery will retry: operationId={}",
                        operation.getOperationId(), e);
            }
        }
        return operations.size();
    }

    private void recoverSafely() {
        int recoverable = 0;
        try {
            recoverable = recoverOnce();
        } catch (RuntimeException e) {
            if (!running) {
                return;
            }
            log.warn("Config State operation recovery scan failed; it will retry", e);
        } finally {
            if (running) {
                nextDelayMs = recoverable > 0
                        ? recoveryIntervalMs
                        : Math.min(MAX_IDLE_INTERVAL_MS,
                                Math.max(recoveryIntervalMs, nextDelayMs * 2L));
                scheduleNext(nextDelayMs);
            }
        }
    }

    private void scheduleNext(long delayMs) {
        ScheduledExecutorService current = executor;
        if (running && current != null && !current.isShutdown()) {
            try {
                current.schedule(this::recoverSafely, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                if (running) {
                    throw e;
                }
            }
        }
    }

    @Destroy
    public void stop() {
        running = false;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }
}
