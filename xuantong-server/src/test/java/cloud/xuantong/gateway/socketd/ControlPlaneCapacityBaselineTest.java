package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateCommand;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in, production-path capacity baseline. It intentionally reports measurements instead
 * of declaring universal performance thresholds; hardware-specific release limits are derived
 * from repeated runs of this same workload.
 */
class ControlPlaneCapacityBaselineTest {
    private static final String ENABLED = "xuantong.capacity.enabled";
    private static final String CLIENTS = "xuantong.capacity.clients";
    private static final String REQUESTS_PER_CLIENT =
            "xuantong.capacity.requestsPerClient";
    private static final String WATCHERS = "xuantong.capacity.watchers";

    @TempDir
    Path tempDirectory;

    @Test
    void measuresFetchAndWatchCapacityWithoutResourceLeaks() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLED),
                "Enable with -D" + ENABLED + "=true");
        int clients = positiveInt(CLIENTS, 16, 1, 2_000);
        int requestsPerClient = positiveInt(REQUESTS_PER_CLIENT, 8, 1, 10_000);
        int watchers = positiveInt(WATCHERS, clients, 0, clients);
        int statePort = freePort();
        int gatewayPort = freePort();
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        ConfigStatePlaneProperties stateProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:" + statePort,
                tempDirectory.resolve("state"),
                true);
        ControlStatePlaneRuntime stateRuntime = new ControlStatePlaneRuntime(
                stateProperties, dispatcher);
        ControlPlaneGatewayServer gatewayServer = null;
        ControlPlaneGatewayRuntime gatewayRuntime = null;
        List<SocketDTransport> transports = new ArrayList<>();
        List<WatchSubscription> subscriptions = new ArrayList<>();
        ExecutorService workers = null;
        try {
            stateRuntime.start();
            ConfigKey key = new ConfigKey("public", "DEFAULT_GROUP", "capacity.value");
            assertEquals(ApplyStatus.APPLIED,
                    submitEventually(stateRuntime, mutation(key, 1, "baseline-v1")).status());

            ControlPlaneGatewayProperties gatewayProperties =
                    new ControlPlaneGatewayProperties(
                            "127.0.0.1",
                            gatewayPort,
                            "cluster-capacity-test",
                            "gateway-capacity-1",
                            1L,
                            10_000L,
                            Math.max(256, clients * 2),
                            Math.min(32, Math.max(4, Runtime.getRuntime()
                                    .availableProcessors() * 2)),
                            Math.max(4_096, clients * 8),
                            5_000L,
                            ControlPlaneGatewayProperties.ClientAuth.NONE);
            gatewayRuntime = new ControlPlaneGatewayRuntime(gatewayProperties);
            PathListenerPlus router = new PathListenerPlus(true);
            router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                    new ControlPlaneGatewayEndpoint(
                            gatewayProperties, gatewayRuntime, dispatcher));
            gatewayServer = new ControlPlaneGatewayServer(
                    gatewayProperties, gatewayRuntime, router, null);
            gatewayServer.start();
            long heapServerBaseline = usedHeap();
            int threadsServerBaseline =
                    ManagementFactory.getThreadMXBean().getThreadCount();

            List<String> addresses = List.of("127.0.0.1:" + gatewayPort);
            ControlPlaneOptions options = new ControlPlaneOptions(
                    "default",
                    "config-default",
                    "cluster-capacity-test",
                    1L,
                    "tcp-default",
                    3_000L,
                    5_000L,
                    10_000L,
                    2_000L);
            CountDownLatch watchUpdateApplied = new CountDownLatch(watchers);
            for (int index = 0; index < clients; index++) {
                SocketDTransport transport = new SocketDTransport(
                        new ClientIdentity(
                                "capacity-client",
                                "capacity-client@" + index),
                        options);
                transport.connect(addresses, key.namespace(), key.group(), "");
                ConfigSnapshot initial = transport.fetch(key.dataId(), 0L);
                assertNotNull(initial);
                assertEquals(1L, initial.getRevision());
                transports.add(transport);
                if (index < watchers) {
                    subscriptions.add(transport.subscribe(1L, batch -> {
                        if (batch.coveredThroughRevision() >= 2L
                                && !batch.events().isEmpty()) {
                            watchUpdateApplied.countDown();
                        }
                        return batch.coveredThroughRevision();
                    }));
                }
            }
            ControlPlaneGatewayRuntime activeRuntime = gatewayRuntime;
            awaitTrue(() -> activeRuntime.activeSessions() == clients
                    && activeRuntime.activeSubscriptions() == watchers,
                    Duration.ofSeconds(10));
            long heapWithClients = usedHeap();
            int threadsWithClients =
                    ManagementFactory.getThreadMXBean().getThreadCount();
            int nettyClientThreadsWithClients = threadCount("nettyTcpClientWork-");
            int watchThreadsWithClients = threadCount("xuantong-control-plane-watch");

            assertEquals(ApplyStatus.APPLIED,
                    submitEventually(stateRuntime, mutation(key, 2, "baseline-v2")).status());
            assertTrue(watchUpdateApplied.await(15, TimeUnit.SECONDS),
                    "Every Watch consumer must commit the new revision");

            List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch start = new CountDownLatch(1);
            workers = Executors.newFixedThreadPool(Math.min(clients, 64));
            List<Future<?>> futures = new ArrayList<>();
            long benchmarkStarted = System.nanoTime();
            for (SocketDTransport transport : transports) {
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int request = 0; request < requestsPerClient; request++) {
                        long started = System.nanoTime();
                        ConfigSnapshot snapshot = transport.fetch(key.dataId(), 2L);
                        latenciesNanos.add(System.nanoTime() - started);
                        if (snapshot == null || snapshot.getRevision() != 2L) {
                            throw new IllegalStateException(
                                    "Capacity fetch returned a stale revision");
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            long benchmarkElapsedNanos = System.nanoTime() - benchmarkStarted;
            int operations = clients * requestsPerClient;
            assertEquals(operations, latenciesNanos.size());

            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
            for (SocketDTransport transport : transports) {
                transport.close();
            }
            transports.clear();
            awaitTrue(() -> activeRuntime.activeSessions() == 0
                            && activeRuntime.activeSubscriptions() == 0
                            && activeRuntime.pendingWatchAcknowledgements() == 0
                            && activeRuntime.inFlightRequests() == 0
                            && activeRuntime.requestAcceptedTotal()
                            == activeRuntime.requestCompletedTotal(),
                    Duration.ofSeconds(15));

            long heapAfter = usedHeap();
            int threadsAfter = awaitStableThreadCount(Duration.ofSeconds(10));
            int nettyClientThreadsAfterClose = threadCount("nettyTcpClientWork-");
            int watchThreadsAfterClose = threadCount("xuantong-control-plane-watch");
            int sharedSocketdWorkThreads = threadCount(
                    "xuantong-socketd-client-work-");
            int maintenanceThreads = threadCount(
                    "xuantong-control-plane-maintenance-");
            StorageSize storage = storageSize(stateProperties.storageDirectory());
            CapacityReport report = CapacityReport.create(
                    clients,
                    watchers,
                    requestsPerClient,
                    benchmarkElapsedNanos,
                    latenciesNanos,
                    heapServerBaseline,
                    heapWithClients,
                    heapAfter,
                    threadsServerBaseline,
                    threadsWithClients,
                    threadsAfter,
                    nettyClientThreadsWithClients,
                    nettyClientThreadsAfterClose,
                    watchThreadsWithClients,
                    watchThreadsAfterClose,
                    sharedSocketdWorkThreads,
                    maintenanceThreads,
                    storage,
                    activeRuntime);
            System.out.println("XUANTONG_CAPACITY_BASELINE " + report.toJson());

            assertTrue(activeRuntime.peakActiveSessions() >= clients);
            assertTrue(activeRuntime.peakActiveSubscriptions() >= watchers);
            assertEquals(0L, activeRuntime.requestOverloadedRejectedTotal());
            assertEquals(0L, activeRuntime.stateCallbackRejectedTotal());
            assertEquals(0, watchThreadsAfterClose,
                    "Per-transport Watch executors must terminate on close");
            assertEquals(0, nettyClientThreadsAfterClose,
                    "Per-connection Netty event loops must terminate on close");
        } finally {
            if (workers != null) {
                workers.shutdownNow();
            }
            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            for (SocketDTransport transport : transports) {
                transport.close();
            }
            if (gatewayServer != null) {
                gatewayServer.stop();
            }
            stateRuntime.stop();
        }
    }

    private StateCommand mutation(ConfigKey key, long revision, String value) {
        return ConfigStateCodec.mutationCommand(
                StateGroupIdHolder.CONFIG,
                "capacity-publish-" + revision,
                new ConfigMutation(
                        new ConfigActor("default", "capacity-test"),
                        key,
                        revision - 1L,
                        ConfigContentDraft.inline(
                                "text", Math.toIntExact(revision),
                                value.getBytes(StandardCharsets.UTF_8)),
                        ConfigContentReference.newContent(),
                        List.of()));
    }

    private ApplyResult submitEventually(
            ControlStatePlaneRuntime runtime, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return runtime.stateClient().submit(command)
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("Config State did not elect a leader") : last;
    }

    private StorageSize storageSize(Path root) throws Exception {
        long walFiles = 0L;
        long walBytes = 0L;
        long snapshotFiles = 0L;
        long snapshotBytes = 0L;
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString();
                    if (name.startsWith("log_")) {
                        walFiles++;
                        walBytes += Files.size(path);
                    } else if (name.startsWith("snapshot.")
                            && !name.endsWith(".md5")) {
                        snapshotFiles++;
                        snapshotBytes += Files.size(path);
                    }
                }
            }
        }
        return new StorageSize(walFiles, walBytes, snapshotFiles, snapshotBytes);
    }

    private void awaitTrue(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.evaluate(), "Condition was not satisfied before timeout");
    }

    private long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private int awaitStableThreadCount(Duration timeout) throws InterruptedException {
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        long quietPeriod = started + Duration.ofSeconds(3).toNanos();
        int previous = -1;
        int stableSamples = 0;
        int current = ManagementFactory.getThreadMXBean().getThreadCount();
        while (System.nanoTime() < deadline) {
            current = ManagementFactory.getThreadMXBean().getThreadCount();
            if (current == previous) {
                stableSamples++;
                if (System.nanoTime() >= quietPeriod && stableSamples >= 5) {
                    return current;
                }
            } else {
                previous = current;
                stableSamples = 0;
            }
            Thread.sleep(100L);
        }
        return current;
    }

    private int threadCount(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private int positiveInt(String name, int defaultValue, int minimum, int maximum) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private record StorageSize(
            long walFiles,
            long walBytes,
            long snapshotFiles,
            long snapshotBytes) {
    }

    private record CapacityReport(
            int clients,
            int watchers,
            int requestsPerClient,
            int operations,
            double throughputPerSecond,
            double p50Milliseconds,
            double p95Milliseconds,
            double p99Milliseconds,
            long clientHeapFootprintBytes,
            long postCloseHeapDeltaBytes,
            int clientThreadFootprint,
            int postCloseThreadDelta,
            int nettyClientThreadsWithClients,
            int nettyClientThreadsAfterClose,
            int watchThreadsWithClients,
            int watchThreadsAfterClose,
            int sharedSocketdWorkThreads,
            int maintenanceThreads,
            int peakSessions,
            int peakSubscriptions,
            int peakInFlightRequests,
            int peakWorkQueueDepth,
            int peakStateCallbackQueueDepth,
            long requestAcceptedTotal,
            long requestCompletedTotal,
            long walFiles,
            long walBytes,
            long snapshotFiles,
            long snapshotBytes) {

        private static CapacityReport create(
                int clients,
                int watchers,
                int requestsPerClient,
                long elapsedNanos,
                List<Long> latencies,
                long heapServerBaseline,
                long heapWithClients,
                long heapAfter,
                int threadsServerBaseline,
                int threadsWithClients,
                int threadsAfter,
                int nettyClientThreadsWithClients,
                int nettyClientThreadsAfterClose,
                int watchThreadsWithClients,
                int watchThreadsAfterClose,
                int sharedSocketdWorkThreads,
                int maintenanceThreads,
                StorageSize storage,
                ControlPlaneGatewayRuntime runtime) {
            List<Long> sorted = latencies.stream().sorted().toList();
            int operations = clients * requestsPerClient;
            double seconds = Math.max(0.000_001D, elapsedNanos / 1_000_000_000D);
            return new CapacityReport(
                    clients,
                    watchers,
                    requestsPerClient,
                    operations,
                    operations / seconds,
                    percentileMilliseconds(sorted, 0.50D),
                    percentileMilliseconds(sorted, 0.95D),
                    percentileMilliseconds(sorted, 0.99D),
                    heapWithClients - heapServerBaseline,
                    heapAfter - heapServerBaseline,
                    threadsWithClients - threadsServerBaseline,
                    threadsAfter - threadsServerBaseline,
                    nettyClientThreadsWithClients,
                    nettyClientThreadsAfterClose,
                    watchThreadsWithClients,
                    watchThreadsAfterClose,
                    sharedSocketdWorkThreads,
                    maintenanceThreads,
                    runtime.peakActiveSessions(),
                    runtime.peakActiveSubscriptions(),
                    runtime.peakInFlightRequests(),
                    runtime.peakWorkQueueDepth(),
                    runtime.peakStateCallbackQueueDepth(),
                    runtime.requestAcceptedTotal(),
                    runtime.requestCompletedTotal(),
                    storage.walFiles(),
                    storage.walBytes(),
                    storage.snapshotFiles(),
                    storage.snapshotBytes());
        }

        private static double percentileMilliseconds(
                List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) {
                return 0D;
            }
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.clamp(index, 0, sorted.size() - 1)) / 1_000_000D;
        }

        private String toJson() {
            return String.format(Locale.ROOT,
                    "{\"clients\":%d,\"watchers\":%d,"
                            + "\"requestsPerClient\":%d,\"operations\":%d,"
                            + "\"throughputPerSecond\":%.2f,"
                            + "\"p50Milliseconds\":%.3f,"
                            + "\"p95Milliseconds\":%.3f,"
                            + "\"p99Milliseconds\":%.3f,"
                            + "\"clientHeapFootprintBytes\":%d,"
                            + "\"postCloseHeapDeltaBytes\":%d,"
                            + "\"clientThreadFootprint\":%d,"
                            + "\"postCloseThreadDelta\":%d,"
                            + "\"nettyClientThreadsWithClients\":%d,"
                            + "\"nettyClientThreadsAfterClose\":%d,"
                            + "\"watchThreadsWithClients\":%d,"
                            + "\"watchThreadsAfterClose\":%d,"
                            + "\"sharedSocketdWorkThreads\":%d,"
                            + "\"maintenanceThreads\":%d,"
                            + "\"peakSessions\":%d,\"peakSubscriptions\":%d,"
                            + "\"peakInFlightRequests\":%d,"
                            + "\"peakWorkQueueDepth\":%d,"
                            + "\"peakStateCallbackQueueDepth\":%d,"
                            + "\"requestAcceptedTotal\":%d,"
                            + "\"requestCompletedTotal\":%d,"
                            + "\"walFiles\":%d,\"walBytes\":%d,"
                            + "\"snapshotFiles\":%d,\"snapshotBytes\":%d}",
                    clients, watchers, requestsPerClient, operations,
                    throughputPerSecond, p50Milliseconds, p95Milliseconds,
                    p99Milliseconds, clientHeapFootprintBytes,
                    postCloseHeapDeltaBytes, clientThreadFootprint,
                    postCloseThreadDelta,
                    nettyClientThreadsWithClients,
                    nettyClientThreadsAfterClose,
                    watchThreadsWithClients,
                    watchThreadsAfterClose,
                    sharedSocketdWorkThreads,
                    maintenanceThreads,
                    peakSessions, peakSubscriptions, peakInFlightRequests,
                    peakWorkQueueDepth, peakStateCallbackQueueDepth,
                    requestAcceptedTotal, requestCompletedTotal,
                    walFiles, walBytes, snapshotFiles, snapshotBytes);
        }
    }

    private static final class StateGroupIdHolder {
        private static final cloud.xuantong.state.api.StateGroupId CONFIG =
                cloud.xuantong.state.api.StateGroupId.config("config-default");
    }
}
