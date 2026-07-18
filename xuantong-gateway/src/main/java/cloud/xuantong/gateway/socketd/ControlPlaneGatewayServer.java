package cloud.xuantong.gateway.socketd;

import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.core.Listener;
import org.noear.socketd.transport.server.Server;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.socketd.SocketdRouter;
import org.noear.solon.server.ServerConstants;
import org.noear.solon.server.ssl.SslConfig;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts exactly one native Socket.D TCP/Netty listener. We intentionally do not use
 * solon-server-socketd here because that plugin starts every provider found in the Netty
 * transport JAR, including UDP, while the 2.0 control plane exposes TCP only.
 */
@Slf4j
@Component
public class ControlPlaneGatewayServer {
    @Inject
    private ControlPlaneGatewayProperties properties;
    @Inject
    private ControlPlaneGatewayRuntime runtime;
    @Inject(required = false)
    private ControlPlaneAuthenticator authenticator;

    private volatile Server server;
    private volatile ThreadPoolExecutor workExecutor;
    private volatile ThreadPoolExecutor stateCallbackExecutor;
    private volatile ScheduledThreadPoolExecutor callbackScheduler;
    private Listener listenerOverride;
    private SSLContext sslContextOverride;
    private boolean sslContextOverrideSet;

    public ControlPlaneGatewayServer() {
    }

    ControlPlaneGatewayServer(
            ControlPlaneGatewayProperties properties,
            ControlPlaneGatewayRuntime runtime,
            Listener listener,
            SSLContext sslContext) {
        this.properties = properties;
        this.runtime = runtime;
        this.listenerOverride = listener;
        this.sslContextOverride = sslContext;
        this.sslContextOverrideSet = true;
    }

    @Init(index = 2_000)
    public synchronized void start() throws Exception {
        if (server != null) {
            return;
        }
        ThreadPoolExecutor executor = newWorkExecutor();
        ThreadPoolExecutor callbackExecutor = newStateCallbackExecutor();
        ScheduledThreadPoolExecutor scheduler = newCallbackScheduler();
        try {
            if (properties.isProduction() && !properties.isApplicationAuthRequired()) {
                throw new IllegalStateException(
                        "Production mode requires security.clientAuthRequired=true");
            }
            if (properties.isApplicationAuthRequired() && authenticator == null) {
                throw new IllegalStateException(
                        "Application authentication is required but no ControlPlaneAuthenticator is configured");
            }
            SslSettings sslSettings = resolveSslSettings();
            SSLContext sslContext = sslSettings.context();
            ControlPlaneGatewayProperties.ClientAuth clientAuth = properties.getTlsClientAuth();
            if (sslContext == null && clientAuth != ControlPlaneGatewayProperties.ClientAuth.NONE) {
                throw new IllegalStateException(
                        "controlPlane.tls.clientAuth requires server.socket.ssl keyStore");
            }
            if (clientAuth != ControlPlaneGatewayProperties.ClientAuth.NONE
                    && !sslSettings.trustStoreConfigured()) {
                throw new IllegalStateException(
                        "controlPlane.tls.clientAuth requires server.socket.ssl trustStore");
            }

            Server candidate = SocketD.createServer("sd:tcp");
            candidate.config(config -> {
                config.host(properties.getHost())
                        .port(properties.getPort())
                        .idleTimeout(properties.getIdleTimeoutMs())
                        .workExecutor(executor);
                if (sslContext != null) {
                    config.sslContext(sslContext)
                            .sslWantClientAuth(clientAuth
                                    == ControlPlaneGatewayProperties.ClientAuth.WANT)
                            .sslNeedClientAuth(clientAuth
                                    == ControlPlaneGatewayProperties.ClientAuth.REQUIRE);
                }
            });
            runtime.markActive(sslContext != null, clientAuth);
            runtime.setStateCallbackExecutor(callbackExecutor);
            runtime.setCallbackScheduler(scheduler);
            runtime.setExecutorTelemetry(executor, callbackExecutor, scheduler);
            candidate.listen(listenerOverride == null
                    ? SocketdRouter.getInstance().getListener() : listenerOverride);
            candidate.start();
            workExecutor = executor;
            stateCallbackExecutor = callbackExecutor;
            callbackScheduler = scheduler;
            server = candidate;
            log.info("Xuantong control-plane Gateway started: schema={}, host={}, port={}, "
                            + "clusterId={}, gatewayId={}, transportGeneration={}, clientAuth={}, "
                            + "applicationAuthRequired={}, authRevalidateIntervalMs={}, "
                            + "helloTimeoutMs={}, "
                            + "watchPollIntervalMs={}, watchIdlePollMaxIntervalMs={}, "
                            + "watchAckTimeoutMs={}, watchStreamMaxLifetimeMs={}, "
                            + "workThreads={}, workQueueCapacity={}, stateCallbackThreads={}, "
                            + "stateCallbackQueueCapacity={}, maxInFlightRequests={}, "
                            + "maxSessions={}, maxSessionsPerTenant={}, "
                            + "maxSessionsPerCredential={}, maxSubscriptionsPerTenant={}, "
                            + "tenantRequestRatePerSecond={}, tenantRequestBurst={}",
                    runtime.transportSchema(), properties.getHost(), properties.getPort(),
                    properties.getClusterId(), properties.getGatewayId(),
                    properties.getTransportGeneration(), clientAuth,
                    properties.isApplicationAuthRequired(),
                    properties.getAuthRevalidateIntervalMs(),
                    properties.getHelloTimeoutMs(),
                    properties.getWatchPollIntervalMs(),
                    properties.getWatchIdlePollMaxIntervalMs(),
                    properties.getWatchAckTimeoutMs(),
                    properties.getWatchStreamMaxLifetimeMs(),
                    properties.getWorkThreads(), properties.getWorkQueueCapacity(),
                    properties.getStateCallbackThreads(),
                    properties.getStateCallbackQueueCapacity(),
                    properties.getMaxInFlightRequests(),
                    properties.getMaxSessions(),
                    properties.getMaxSessionsPerTenant(),
                    properties.getMaxSessionsPerCredential(),
                    properties.getMaxSubscriptionsPerTenant(),
                    properties.getTenantRequestRatePerSecond(),
                    properties.getTenantRequestBurst());
        } catch (Exception e) {
            runtime.markClosed();
            runtime.setStateCallbackExecutor(null);
            runtime.setCallbackScheduler(null);
            runtime.setExecutorTelemetry(null, null, null);
            shutdownExecutor(executor);
            shutdownExecutor(callbackExecutor);
            shutdownScheduler(scheduler);
            throw e;
        }
    }

    @Destroy
    public synchronized void stop() {
        Server current = server;
        server = null;
        if (current == null) {
            return;
        }
        runtime.beginDrain();
        current.prestop();
        boolean drained = awaitDrain(Duration.ofMillis(properties.getDrainTimeoutMs()));
        current.stop();
        ThreadPoolExecutor executor = workExecutor;
        workExecutor = null;
        ThreadPoolExecutor callbackExecutor = stateCallbackExecutor;
        stateCallbackExecutor = null;
        ScheduledThreadPoolExecutor scheduler = callbackScheduler;
        callbackScheduler = null;
        shutdownExecutor(executor);
        shutdownExecutor(callbackExecutor);
        shutdownScheduler(scheduler);
        runtime.setStateCallbackExecutor(null);
        runtime.setCallbackScheduler(null);
        runtime.setExecutorTelemetry(null, null, null);
        runtime.markClosed();
        if (drained) {
            log.info("Xuantong control-plane Gateway stopped after graceful drain: gatewayId={}",
                    properties.getGatewayId());
        } else {
            log.warn("Xuantong control-plane Gateway forced final close after drain timeout: "
                            + "gatewayId={}, inFlightRequests={}, queuedTasks={}",
                    properties.getGatewayId(), runtime.inFlightRequests(),
                    executor == null ? 0 : executor.getQueue().size());
        }
    }

    private SslSettings resolveSslSettings() throws Exception {
        if (sslContextOverrideSet) {
            return new SslSettings(sslContextOverride, sslContextOverride != null);
        }
        SslConfig sslConfig = new SslConfig(ServerConstants.SIGNAL_SOCKET);
        if (!sslConfig.isSslEnable()) {
            return new SslSettings(null, false);
        }
        var sslProperties = sslConfig.getProps();
        boolean trustStoreConfigured = sslProperties != null
                && sslProperties.getSslTrustStore() != null
                && !sslProperties.getSslTrustStore().isBlank();
        return new SslSettings(sslConfig.getSslContext(), trustStoreConfigured);
    }

    private ThreadPoolExecutor newWorkExecutor() {
        int threads = properties.getWorkThreads();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getWorkQueueCapacity()),
                new GatewayThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    private ThreadPoolExecutor newStateCallbackExecutor() {
        int threads = properties.getStateCallbackThreads();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getStateCallbackQueueCapacity()),
                new StateCallbackThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    private ScheduledThreadPoolExecutor newCallbackScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1, new CallbackSchedulerThreadFactory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private boolean awaitDrain(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!runtime.awaitRequestsDrained(timeout)) {
                return false;
            }
            ThreadPoolExecutor executor = workExecutor;
            while (executor != null
                    && (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty())) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                Thread.sleep(10L);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void shutdownExecutor(ThreadPoolExecutor executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void shutdownScheduler(ScheduledThreadPoolExecutor scheduler) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    ThreadPoolExecutor workExecutor() {
        return workExecutor;
    }

    private static final class GatewayThreadFactory implements ThreadFactory {
        private final AtomicInteger threadIds = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task,
                    "xuantong-gateway-work-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class StateCallbackThreadFactory implements ThreadFactory {
        private final AtomicInteger threadIds = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task,
                    "xuantong-state-callback-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class CallbackSchedulerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "xuantong-callback-scheduler");
            thread.setDaemon(true);
            return thread;
        }
    }

    private record SslSettings(SSLContext context, boolean trustStoreConfigured) {
    }
}
