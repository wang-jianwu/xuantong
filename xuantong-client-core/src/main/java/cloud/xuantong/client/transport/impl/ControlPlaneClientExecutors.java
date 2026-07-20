package cloud.xuantong.client.transport.impl;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM-scoped bounded executors shared by every Xuantong Socket.D control connection. */
final class ControlPlaneClientExecutors {
    private static final int WORK_THREADS = Math.max(
            4, Math.min(16, Runtime.getRuntime().availableProcessors()));
    private static final int WORK_QUEUE_CAPACITY = 8_192;
    private static final ThreadPoolExecutor SOCKETD_WORK = new ThreadPoolExecutor(
            WORK_THREADS,
            WORK_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
            new NamedDaemonThreadFactory("xuantong-socketd-client-work-"),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private static final ScheduledThreadPoolExecutor MAINTENANCE =
            new ScheduledThreadPoolExecutor(
                    2,
                    new NamedDaemonThreadFactory(
                            "xuantong-control-plane-maintenance-"));

    static {
        SOCKETD_WORK.prestartAllCoreThreads();
        MAINTENANCE.setRemoveOnCancelPolicy(true);
        MAINTENANCE.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        MAINTENANCE.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    private ControlPlaneClientExecutors() {
    }

    static ExecutorService socketdWorkExecutor() {
        return SOCKETD_WORK;
    }

    static ScheduledThreadPoolExecutor maintenanceScheduler() {
        return MAINTENANCE;
    }

    static int workThreadLimit() {
        return WORK_THREADS;
    }

    static int workQueueDepth() {
        return SOCKETD_WORK.getQueue().size();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
