package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.metrics.ControlPlaneTransportMetricsSnapshot;
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
import cloud.xuantong.raft.ratis.RatisGroupRuntimeStatus;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevisionType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.io.BufferedWriter;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameterized acceptance load for the production control-plane topology:
 * three co-located Gateway/State processes and one three-voter Config Raft Group.
 *
 * <p>Every client keeps one active Socket.D Session. Its address order is rotated
 * to distribute initial load, while one logical Fetch still reaches one Gateway.
 * The optional fault phase stops Gateway A only; affected clients must open one
 * sequential standby without opening or sending to every known address.</p>
 */
class ControlPlaneProductionTopologyLoadTest {
    private static final String PREFIX = "xuantong.topology.";
    private static final int TOPOLOGY_SIZE = 3;
    private static final StateGroupId CONFIG_GROUP =
            StateGroupId.config("config-default");

    @TempDir
    Path tempDirectory;

    @Test
    void runsThreeVoterMultiGatewayTopologyLoad() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PREFIX + "enabled"),
                "Enable with -D" + PREFIX + "enabled=true");
        LoadProfile profile = LoadProfile.fromSystemProperties();
        List<Integer> ports = freePorts(TOPOLOGY_SIZE * 2);
        String peers = peers(ports.subList(0, TOPOLOGY_SIZE));
        List<ControlPlaneRequestDispatcher> dispatchers = new ArrayList<>();
        List<ConfigStatePlaneProperties> stateProperties = new ArrayList<>();
        List<ControlStatePlaneRuntime> stateRuntimes = new ArrayList<>();
        List<GatewayHarness> gateways = new ArrayList<>();
        List<ClientHarness> clients = new ArrayList<>();
        List<WatchSubscription> subscriptions = new ArrayList<>();
        ExecutorService bootstrap = null;
        ExecutorService fetchWorkers = null;
        ExecutorService publisher = null;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        LongAdder fetchSucceeded = new LongAdder();
        LongAdder fetchFailed = new LongAdder();
        LongAdder revisionRegressed = new LongAdder();
        LongAdder publishSucceeded = new LongAdder();
        LongAdder publishFailed = new LongAdder();
        LatencyHistogram fetchLatency = new LatencyHistogram();
        AtomicLong latestDecisionRevision = new AtomicLong(1L);
        AtomicLong latestEventRevision = new AtomicLong();
        AtomicLong latestAppliedIndex = new AtomicLong();
        long startedEpochMs = System.currentTimeMillis();
        long workloadStartedNanos = 0L;
        long workloadElapsedNanos = 0L;
        List<RatisGroupRuntimeStatus> stateStatuses = List.of();
        List<StorageSize> stateStorage = List.of();
        List<GatewayMetrics> gatewayMetrics = List.of();
        List<ControlPlaneTransportMetricsSnapshot> clientAfterClose = List.of();
        try {
            for (int index = 0; index < TOPOLOGY_SIZE; index++) {
                ControlPlaneRequestDispatcher dispatcher =
                        new ControlPlaneRequestDispatcher();
                ConfigStatePlaneProperties properties =
                        new ConfigStatePlaneProperties(
                                true,
                                "state-" + (index + 1),
                                CONFIG_GROUP.value(),
                                peers,
                                tempDirectory.resolve("state-" + (index + 1)),
                                false);
                dispatchers.add(dispatcher);
                stateProperties.add(properties);
                stateRuntimes.add(new ControlStatePlaneRuntime(
                        properties, dispatcher));
            }
            bootstrap = Executors.newFixedThreadPool(
                    TOPOLOGY_SIZE, named("xuantong-topology-bootstrap-"));
            List<Future<?>> bootstrapTasks = new ArrayList<>();
            for (ControlStatePlaneRuntime runtime : stateRuntimes) {
                bootstrapTasks.add(bootstrap.submit(() -> {
                    runtime.start();
                    return null;
                }));
            }
            for (Future<?> task : bootstrapTasks) {
                task.get(20, TimeUnit.SECONDS);
            }
            shutdownNow(bootstrap);
            bootstrap = null;

            ConfigKey key = new ConfigKey(
                    "public", "DEFAULT_GROUP", "topology.payload");
            ApplyResult initial = submitEventually(
                    stateRuntimes.get(0),
                    mutation(key, 1L, payload(1L, profile.payloadBytes())));
            assertEquals(ApplyStatus.APPLIED, initial.status());
            latestEventRevision.set(revision(
                    initial, StateRevisionType.CONFIG_EVENT));
            latestAppliedIndex.set(initial.appliedIndex());

            for (int index = 0; index < TOPOLOGY_SIZE; index++) {
                gateways.add(gateway(
                        "gateway-topology-" + (index + 1),
                        ports.get(TOPOLOGY_SIZE + index),
                        dispatchers.get(index),
                        profile));
            }
            for (GatewayHarness gateway : gateways) {
                gateway.start();
            }

            List<String> canonicalAddresses = gateways.stream()
                    .map(GatewayHarness::address)
                    .toList();
            ControlPlaneOptions options = new ControlPlaneOptions(
                    "default",
                    CONFIG_GROUP.value(),
                    "cluster-production-topology-test",
                    1L,
                    "tcp-default",
                    3_000L,
                    5_000L,
                    10_000L,
                    2_000L);
            for (int index = 0; index < profile.clients(); index++) {
                int[] gatewayOrder = rotatedOrder(index % TOPOLOGY_SIZE);
                List<String> addresses = new ArrayList<>(TOPOLOGY_SIZE);
                for (int gatewayIndex : gatewayOrder) {
                    addresses.add(canonicalAddresses.get(gatewayIndex));
                }
                CountingSocketDTransport transport =
                        new CountingSocketDTransport(
                                new ClientIdentity(
                                        "topology-client",
                                        "topology-client@" + index),
                                options,
                                TOPOLOGY_SIZE);
                ClientHarness client = new ClientHarness(
                        index,
                        gatewayOrder,
                        transport,
                        new AtomicLong(),
                        new AtomicLong(latestEventRevision.get()));
                clients.add(client);
                transport.connect(
                        addresses, key.namespace(), key.group(), "");
                ConfigSnapshot snapshot = transport.fetch(key.dataId(), 0L);
                assertNotNull(snapshot);
                assertEquals(1L, snapshot.getRevision());
                client.observedDecision().set(snapshot.getRevision());
                if (index < profile.watchers()) {
                    WatchSubscription subscription = transport.subscribe(
                            client.watchCursor().get(),
                            batch -> {
                                long covered = batch.coveredThroughRevision();
                                long previous = client.watchCursor()
                                        .getAndAccumulate(covered, Math::max);
                                if (covered < previous) {
                                    revisionRegressed.increment();
                                    fatal.compareAndSet(null,
                                            new IllegalStateException(
                                                    "Watch cursor regressed for client "
                                                            + client.index()));
                                    stop.set(true);
                                }
                                return covered;
                            });
                    subscriptions.add(subscription);
                }
            }
            awaitInitialDistribution(gateways, clients, profile.watchers());
            assertSingleFetchPerClient(gateways, clients, key);

            long deadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(profile.durationSeconds()).toNanos();
            workloadStartedNanos = System.nanoTime();
            RatePacer pacer = new RatePacer(
                    profile.fetchRatePerSecond(), workloadStartedNanos);
            AtomicInteger nextClient = new AtomicInteger();
            fetchWorkers = Executors.newFixedThreadPool(
                    profile.fetchConcurrency(), named("xuantong-topology-fetch-"));
            List<Future<?>> fetchTasks = new ArrayList<>();
            for (int worker = 0; worker < profile.fetchConcurrency(); worker++) {
                fetchTasks.add(fetchWorkers.submit(() -> {
                    while (!stop.get() && System.nanoTime() < deadlineNanos) {
                        pacer.awaitNext(deadlineNanos);
                        if (stop.get() || System.nanoTime() >= deadlineNanos) {
                            break;
                        }
                        ClientHarness client = clients.get(Math.floorMod(
                                nextClient.getAndIncrement(), clients.size()));
                        long requestStarted = System.nanoTime();
                        try {
                            ConfigSnapshot snapshot = client.transport().fetch(
                                    key.dataId(), 0L);
                            fetchLatency.record(
                                    System.nanoTime() - requestStarted);
                            if (snapshot == null) {
                                fetchFailed.increment();
                                continue;
                            }
                            long previous = client.observedDecision()
                                    .getAndAccumulate(
                                            snapshot.getRevision(), Math::max);
                            if (snapshot.getRevision() < previous) {
                                revisionRegressed.increment();
                            } else {
                                fetchSucceeded.increment();
                            }
                            assertOneActiveSession(client);
                        } catch (Throwable e) {
                            fetchLatency.record(
                                    System.nanoTime() - requestStarted);
                            fetchFailed.increment();
                            fatal.compareAndSet(null, e);
                            stop.set(true);
                        }
                    }
                    return null;
                }));
            }

            if (profile.publishRatePerMinute() > 0) {
                publisher = Executors.newSingleThreadExecutor(
                        named("xuantong-topology-publish-"));
                publisher.submit(() -> {
                    long publishIntervalNanos = Math.max(
                            1L,
                            Math.round(60_000_000_000D
                                    / profile.publishRatePerMinute()));
                    long nextPublish = System.nanoTime()
                            + publishIntervalNanos;
                    while (!stop.get() && nextPublish < deadlineNanos) {
                        try {
                            parkUntil(nextPublish);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (stop.get() || System.nanoTime() >= deadlineNanos) {
                            return;
                        }
                        long targetRevision =
                                latestDecisionRevision.get() + 1L;
                        try {
                            ApplyResult applied = submitEventually(
                                    stateRuntimes.get(0),
                                    mutation(
                                            key,
                                            targetRevision,
                                            payload(targetRevision,
                                                    profile.payloadBytes())));
                            if (applied.status() != ApplyStatus.APPLIED) {
                                throw new IllegalStateException(
                                        "Unexpected publish status "
                                                + applied.status());
                            }
                            latestDecisionRevision.set(targetRevision);
                            latestEventRevision.set(revision(
                                    applied,
                                    StateRevisionType.CONFIG_EVENT));
                            latestAppliedIndex.set(applied.appliedIndex());
                            publishSucceeded.increment();
                        } catch (Throwable e) {
                            publishFailed.increment();
                            fatal.compareAndSet(null, e);
                            stop.set(true);
                            return;
                        }
                        nextPublish += publishIntervalNanos;
                    }
                });
            }

            if (profile.failoverEnabled()) {
                long failoverAt = workloadStartedNanos
                        + Duration.ofSeconds(
                                Math.max(1, profile.durationSeconds() / 2L))
                        .toNanos();
                parkUntil(Math.min(failoverAt, deadlineNanos));
                gateways.get(0).stop();
                awaitSequentialFailover(gateways, clients, key);
            }

            long taskTimeoutSeconds = profile.durationSeconds() + 30L;
            for (Future<?> task : fetchTasks) {
                task.get(taskTimeoutSeconds, TimeUnit.SECONDS);
            }
            workloadElapsedNanos = System.nanoTime() - workloadStartedNanos;
            stop.set(true);
            shutdownNow(publisher);
            publisher = null;
            shutdownNow(fetchWorkers);
            fetchWorkers = null;

            long requiredEventRevision = latestEventRevision.get();
            if (!subscriptions.isEmpty() && publishSucceeded.sum() > 0L) {
                awaitTrue(() -> clients.stream()
                                .limit(profile.watchers())
                                .allMatch(client -> client.watchCursor().get()
                                        >= requiredEventRevision),
                        Duration.ofSeconds(30));
            }
            awaitStateReplication(
                    stateRuntimes, latestAppliedIndex.get());
            assertSingleFetchPerClient(gateways, clients, key);

            stateStatuses = stateRuntimes.stream()
                    .map(runtime -> stateStatus(runtime, CONFIG_GROUP))
                    .toList();
            assertEquals(1L, stateStatuses.stream()
                    .filter(RatisGroupRuntimeStatus::leader)
                    .count());
            assertTrue(stateStatuses.stream()
                    .allMatch(RatisGroupRuntimeStatus::alive));
            assertTrue(stateStatuses.stream()
                    .allMatch(status -> status.lastCommittedIndex()
                                    >= latestAppliedIndex.get()
                            && status.lastAppliedIndex()
                                    >= latestAppliedIndex.get()));
            stateStorage = stateProperties.stream()
                    .map(properties -> storageSize(
                            properties.storageDirectory()))
                    .toList();

            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
            for (ClientHarness client : clients) {
                client.transport().close();
            }
            awaitGatewaysClosed(gateways);
            gatewayMetrics = gateways.stream()
                    .map(GatewayHarness::metrics)
                    .toList();
            assertTrue(gatewayMetrics.stream()
                    .allMatch(metrics -> metrics.requestAcceptedTotal() > 0L));
            clientAfterClose = clients.stream()
                    .map(client -> client.transport().metricsSnapshot())
                    .toList();
            assertTrue(clientAfterClose.stream().allMatch(
                    metrics -> metrics.activeSessions() == 0
                            && metrics.inFlightRequests() == 0
                            && metrics.registeredWatches() == 0
                            && metrics.activeSubscribeStreams() == 0
                            && metrics.closed()));

            writeReport(
                    profile,
                    startedEpochMs,
                    workloadElapsedNanos,
                    fetchSucceeded.sum(),
                    fetchFailed.sum(),
                    revisionRegressed.sum(),
                    publishSucceeded.sum(),
                    publishFailed.sum(),
                    latestDecisionRevision.get(),
                    latestEventRevision.get(),
                    fetchLatency,
                    gatewayMetrics,
                    stateStatuses,
                    stateStorage,
                    clientAfterClose);

            assertNull(fatal.get(), "Topology load worker failed: " + fatal.get());
            assertTrue(fetchSucceeded.sum() > 0L);
            assertEquals(0L, fetchFailed.sum());
            assertEquals(0L, revisionRegressed.sum());
            assertEquals(0L, publishFailed.sum());
            assertTrue(gatewayMetrics.stream().allMatch(
                    metrics -> metrics.requestAcceptedTotal()
                            == metrics.requestCompletedTotal()));
            assertTrue(gatewayMetrics.stream().allMatch(
                    metrics -> metrics.tenantRateLimitedTotal() == 0L
                            && metrics.overloadedRejectedTotal() == 0L
                            && metrics.stateCallbackRejectedTotal() == 0L));
        } finally {
            stop.set(true);
            shutdownNow(publisher);
            shutdownNow(fetchWorkers);
            shutdownNow(bootstrap);
            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            for (ClientHarness client : clients) {
                client.transport().close();
            }
            for (int index = gateways.size() - 1; index >= 0; index--) {
                gateways.get(index).stop();
            }
            stopStateRuntimes(stateRuntimes);
        }
    }

    private GatewayHarness gateway(
            String gatewayId,
            int port,
            ControlPlaneRequestDispatcher dispatcher,
            LoadProfile profile) {
        int tenantRate = profile.fetchRatePerSecond() == 0
                ? 100_000_000
                : Math.max(100_000, profile.fetchRatePerSecond() * 4);
        int tenantBurst = Math.max(
                100_000,
                Math.max(profile.clients() + profile.watchers(), tenantRate * 2));
        ControlPlaneGatewayProperties properties =
                new ControlPlaneGatewayProperties(
                        "127.0.0.1",
                        port,
                        "cluster-production-topology-test",
                        gatewayId,
                        1L,
                        10_000L,
                        Math.max(256, profile.fetchConcurrency() * 8),
                    Math.clamp(Runtime.getRuntime()
                        .availableProcessors() * 2L, 4, 64),
                        Math.max(4_096, profile.fetchConcurrency() * 32),
                        tenantRate,
                        tenantBurst,
                        5_000L,
                        ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime runtime =
                new ControlPlaneGatewayRuntime(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(
                        properties, runtime, dispatcher));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        return new GatewayHarness(properties, runtime, server);
    }

    private void awaitInitialDistribution(
            List<GatewayHarness> gateways,
            List<ClientHarness> clients,
            int watchers) throws Exception {
        awaitTrue(() -> gateways.stream()
                        .mapToInt(gateway -> gateway.runtime.activeSessions())
                        .sum() == clients.size()
                        && gateways.stream()
                        .mapToInt(gateway -> gateway.runtime.activeSubscriptions())
                        .sum() == watchers,
                Duration.ofSeconds(20));
        for (ClientHarness client : clients) {
            assertEquals(0, client.transport().activeGatewayIndex());
            assertEquals(1, client.transport().openAttempts(0));
            assertEquals(0, client.transport().openAttempts(1));
            assertEquals(0, client.transport().openAttempts(2));
            assertOneActiveSession(client);
        }
        for (int gatewayIndex = 0;
                gatewayIndex < TOPOLOGY_SIZE;
                gatewayIndex++) {
            int expectedSessions = 0;
            int expectedSubscriptions = 0;
            for (ClientHarness client : clients) {
                if (client.gatewayOrder()[0] == gatewayIndex) {
                    expectedSessions++;
                    if (client.index() < watchers) {
                        expectedSubscriptions++;
                    }
                }
            }
            assertEquals(expectedSessions,
                    gateways.get(gatewayIndex).runtime.activeSessions());
            assertEquals(expectedSubscriptions,
                    gateways.get(gatewayIndex).runtime.activeSubscriptions());
        }
    }

    private void awaitSequentialFailover(
            List<GatewayHarness> gateways,
            List<ClientHarness> clients,
            ConfigKey key) throws Exception {
        for (ClientHarness client : clients) {
            if (client.gatewayOrder()[0] == 0
                    && client.transport().activeGatewayIndex() != 1) {
                ConfigSnapshot snapshot = client.transport().fetch(
                        key.dataId(), client.observedDecision().get());
                assertNotNull(snapshot);
                client.observedDecision().accumulateAndGet(
                        snapshot.getRevision(), Math::max);
            }
        }
        awaitTrue(() -> {
            for (ClientHarness client : clients) {
                if (client.gatewayOrder()[0] == 0) {
                    if (client.transport().activeGatewayIndex() != 1
                            || client.transport().openAttempts(1) != 1) {
                        return false;
                    }
                }
                if (client.transport().metricsSnapshot().activeSessions() != 1) {
                    return false;
                }
            }
            return gateways.get(0).runtime.activeSessions() == 0
                    && gateways.stream()
                    .mapToInt(gateway -> gateway.runtime.activeSessions())
                    .sum() == clients.size();
        }, Duration.ofSeconds(30));
        for (ClientHarness client : clients) {
            if (client.gatewayOrder()[0] == 0) {
                assertEquals(1, client.transport().openAttempts(0));
                assertEquals(1, client.transport().openAttempts(1));
                assertEquals(0, client.transport().openAttempts(2));
            } else {
                assertEquals(0, client.transport().activeGatewayIndex());
                assertEquals(1, client.transport().openAttempts(0));
                assertEquals(0, client.transport().openAttempts(1));
                assertEquals(0, client.transport().openAttempts(2));
            }
            assertOneActiveSession(client);
        }
    }

    private void assertSingleFetchPerClient(
            List<GatewayHarness> gateways,
            List<ClientHarness> clients,
            ConfigKey key) throws Exception {
        awaitGatewayIdle(gateways);
        long[] acceptedBefore = gateways.stream()
                .mapToLong(gateway -> gateway.runtime.requestAcceptedTotal())
                .toArray();
        int[] expectedByGateway = new int[TOPOLOGY_SIZE];
        for (ClientHarness client : clients) {
            int localGatewayIndex = client.transport().activeGatewayIndex();
            assertTrue(localGatewayIndex >= 0);
            int globalGatewayIndex =
                    client.gatewayOrder()[localGatewayIndex];
            expectedByGateway[globalGatewayIndex]++;
            ConfigSnapshot snapshot = client.transport().fetch(
                    key.dataId(), client.observedDecision().get());
            assertNotNull(snapshot);
            long previous = client.observedDecision().getAndAccumulate(
                    snapshot.getRevision(), Math::max);
            assertTrue(snapshot.getRevision() >= previous);
            assertOneActiveSession(client);
        }
        awaitGatewayIdle(gateways);
        long totalDelta = 0L;
        for (int index = 0; index < gateways.size(); index++) {
            long delta = gateways.get(index).runtime.requestAcceptedTotal()
                    - acceptedBefore[index];
            assertEquals(expectedByGateway[index], delta,
                    "One logical Fetch must reach exactly one active Gateway");
            totalDelta += delta;
        }
        assertEquals(clients.size(), totalDelta,
                "Fetch requests must not fan out across known Gateways");
    }

    private void assertOneActiveSession(ClientHarness client) {
        ControlPlaneTransportMetricsSnapshot metrics =
                client.transport().metricsSnapshot();
        assertEquals(1, metrics.activeSessions(),
                "Each client must keep exactly one active Gateway Session");
    }

    private void awaitStateReplication(
            List<ControlStatePlaneRuntime> runtimes,
            long requiredAppliedIndex) throws Exception {
        awaitTrue(() -> {
            int leaders = 0;
            for (ControlStatePlaneRuntime runtime : runtimes) {
                RatisGroupRuntimeStatus status =
                        runtime.stateGroupRuntimeStatus(CONFIG_GROUP);
                if (!status.alive()
                        || status.lastCommittedIndex() < requiredAppliedIndex
                        || status.lastAppliedIndex() < requiredAppliedIndex) {
                    return false;
                }
                if (status.leader()) {
                    leaders++;
                }
            }
            return leaders == 1;
        }, Duration.ofSeconds(30));
    }

    private void awaitGatewayIdle(List<GatewayHarness> gateways)
            throws Exception {
        awaitTrue(() -> gateways.stream().allMatch(gateway ->
                        gateway.runtime.inFlightRequests() == 0
                                && gateway.runtime.requestAcceptedTotal()
                                == gateway.runtime.requestCompletedTotal()),
                Duration.ofSeconds(10));
    }

    private void awaitGatewaysClosed(List<GatewayHarness> gateways)
            throws Exception {
        awaitTrue(() -> gateways.stream().allMatch(gateway ->
                        gateway.runtime.activeSessions() == 0
                                && gateway.runtime.activeSubscriptions() == 0
                                && gateway.runtime.pendingWatchAcknowledgements() == 0
                                && gateway.runtime.inFlightRequests() == 0
                                && gateway.runtime.requestAcceptedTotal()
                                == gateway.runtime.requestCompletedTotal()),
                Duration.ofSeconds(20));
        for (GatewayHarness gateway : gateways) {
            assertEquals(gateway.runtime.sessionOpenedTotal(),
                    gateway.runtime.sessionClosedTotal());
            assertEquals(gateway.runtime.subscriptionOpenedTotal(),
                    gateway.runtime.subscriptionClosedTotal());
        }
    }

    private StateCommand mutation(
            ConfigKey key, long revision, byte[] value) {
        return ConfigStateCodec.mutationCommand(
                CONFIG_GROUP,
                "topology-publish-" + revision,
                new ConfigMutation(
                        new ConfigActor("default", "topology-load"),
                        key,
                        revision - 1L,
                        ConfigContentDraft.inline(
                                "text", Math.toIntExact(revision), value),
                        ConfigContentReference.newContent(),
                        List.of()));
    }

    private byte[] payload(long revision, int size) {
        byte[] prefix = ("revision=" + revision + ";")
                .getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[size];
        for (int index = 0; index < result.length; index++) {
            result[index] = index < prefix.length
                    ? prefix[index]
                    : (byte) 'x';
        }
        return result;
    }

    private long revision(ApplyResult result, StateRevisionType type) {
        return result.revisions().stream()
                .filter(value -> value.type() == type)
                .findFirst()
                .orElseThrow()
                .value();
    }

    private ApplyResult submitEventually(
            ControlStatePlaneRuntime runtime,
            StateCommand command) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(15).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return runtime.stateClient().submit(command)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException(
                        "Config State did not complete the mutation")
                : last;
    }

    private void writeReport(
            LoadProfile profile,
            long startedEpochMs,
            long workloadElapsedNanos,
            long fetchSucceeded,
            long fetchFailed,
            long revisionRegressed,
            long publishSucceeded,
            long publishFailed,
            long latestDecisionRevision,
            long latestEventRevision,
            LatencyHistogram latency,
            List<GatewayMetrics> gateways,
            List<RatisGroupRuntimeStatus> states,
            List<StorageSize> storage,
            List<ControlPlaneTransportMetricsSnapshot> clientsAfterClose)
            throws Exception {
        Path reportPath = profile.reportPath().isBlank()
                ? tempDirectory.resolve("production-topology-load.jsonl")
                : Path.of(profile.reportPath()).toAbsolutePath().normalize();
        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        double fetchPerSecond = workloadElapsedNanos <= 0L
                ? 0D
                : fetchSucceeded * 1_000_000_000D / workloadElapsedNanos;
        StringBuilder json = new StringBuilder(4_096);
        json.append('{')
                .append("\"type\":\"summary\",")
                .append("\"runLabel\":\"").append(json(profile.runLabel()))
                .append("\",")
                .append("\"buildRevision\":\"")
                .append(json(profile.buildRevision())).append("\",")
                .append("\"buildState\":\"")
                .append(json(profile.buildState())).append("\",")
                .append("\"startedEpochMs\":").append(startedEpochMs)
                .append(',')
                .append("\"durationMs\":")
                .append(TimeUnit.NANOSECONDS.toMillis(workloadElapsedNanos))
                .append(',')
                .append("\"topology\":{")
                .append("\"transport\":\"native-socketd-tcp\",")
                .append("\"processModel\":\"single-test-jvm\",")
                .append("\"gatewayCount\":3,")
                .append("\"stateVoterCount\":3,")
                .append("\"coLocatedGatewayAndState\":true,")
                .append("\"singleActiveSessionPerClient\":true,")
                .append("\"sequentialFailover\":")
                .append(profile.failoverEnabled()).append("},")
                .append("\"profile\":").append(profile.toJson()).append(',')
                .append("\"result\":{")
                .append("\"fetchSucceeded\":").append(fetchSucceeded)
                .append(',')
                .append("\"fetchPerSecond\":")
                .append(Double.toString(fetchPerSecond)).append(',')
                .append("\"fetchFailed\":").append(fetchFailed).append(',')
                .append("\"revisionRegressed\":")
                .append(revisionRegressed).append(',')
                .append("\"publishSucceeded\":")
                .append(publishSucceeded).append(',')
                .append("\"publishFailed\":").append(publishFailed)
                .append(',')
                .append("\"latestDecisionRevision\":")
                .append(latestDecisionRevision).append(',')
                .append("\"latestEventRevision\":")
                .append(latestEventRevision).append(',')
                .append("\"fetchP50UpperBoundMs\":")
                .append(latency.percentileUpperBoundMs(0.50)).append(',')
                .append("\"fetchP95UpperBoundMs\":")
                .append(latency.percentileUpperBoundMs(0.95)).append(',')
                .append("\"fetchP99UpperBoundMs\":")
                .append(latency.percentileUpperBoundMs(0.99)).append(',')
                .append("\"fetchMaxMs\":")
                .append(TimeUnit.NANOSECONDS.toMillis(latency.maxNanos()))
                .append("},\"gateways\":[");
        for (int index = 0; index < gateways.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(gateways.get(index).toJson());
        }
        json.append("],\"stateNodes\":[");
        for (int index = 0; index < states.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(stateToJson(states.get(index), storage.get(index)));
        }
        int activeSessions = clientsAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::activeSessions)
                .sum();
        int inFlightRequests = clientsAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::inFlightRequests)
                .sum();
        int watches = clientsAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::registeredWatches)
                .sum();
        int subscribeStreams = clientsAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::activeSubscribeStreams)
                .sum();
        json.append("],\"clientAfterClose\":{")
                .append("\"activeSessions\":").append(activeSessions)
                .append(',')
                .append("\"inFlightRequests\":").append(inFlightRequests)
                .append(',')
                .append("\"registeredWatches\":").append(watches)
                .append(',')
                .append("\"activeSubscribeStreams\":")
                .append(subscribeStreams).append("}}\n");
        try (BufferedWriter writer = Files.newBufferedWriter(
                reportPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write(json.toString());
        }
    }

    private String stateToJson(
            RatisGroupRuntimeStatus status, StorageSize storage) {
        return new StringBuilder(512)
                .append('{')
                .append("\"nodeId\":\"").append(json(status.nodeId()))
                .append("\",")
                .append("\"groupId\":\"")
                .append(json(status.groupId().canonicalName()))
                .append("\",")
                .append("\"alive\":").append(status.alive()).append(',')
                .append("\"leader\":").append(status.leader()).append(',')
                .append("\"leaderReady\":")
                .append(status.leaderReady()).append(',')
                .append("\"leaderId\":\"").append(json(status.leaderId()))
                .append("\",")
                .append("\"currentTerm\":").append(status.currentTerm())
                .append(',')
                .append("\"lastCommittedIndex\":")
                .append(status.lastCommittedIndex()).append(',')
                .append("\"lastAppliedIndex\":")
                .append(status.lastAppliedIndex()).append(',')
                .append("\"walFiles\":").append(storage.walFiles())
                .append(',')
                .append("\"walBytes\":").append(storage.walBytes())
                .append(',')
                .append("\"snapshotFiles\":")
                .append(storage.snapshotFiles()).append(',')
                .append("\"snapshotBytes\":")
                .append(storage.snapshotBytes()).append('}')
                .toString();
    }

    private RatisGroupRuntimeStatus stateStatus(
            ControlStatePlaneRuntime runtime, StateGroupId groupId) {
        try {
            return runtime.stateGroupRuntimeStatus(groupId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read local State Node status", e);
        }
    }

    private StorageSize storageSize(Path root) {
        long walFiles = 0L;
        long walBytes = 0L;
        long snapshotFiles = 0L;
        long snapshotBytes = 0L;
        try {
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
            return new StorageSize(
                    walFiles, walBytes, snapshotFiles, snapshotBytes);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to inspect State storage " + root, e);
        }
    }

    private List<Integer> freePorts(int count) throws Exception {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                sockets.add(new ServerSocket(0));
            }
            return sockets.stream()
                    .map(ServerSocket::getLocalPort)
                    .toList();
        } catch (SocketException e) {
            throw new IllegalStateException(
                    "Production topology testing requires local socket binding",
                    e);
        } finally {
            for (ServerSocket socket : sockets) {
                socket.close();
            }
        }
    }

    private String peers(List<Integer> ports) {
        List<String> peers = new ArrayList<>(ports.size());
        for (int index = 0; index < ports.size(); index++) {
            peers.add("state-" + (index + 1)
                    + "@127.0.0.1:" + ports.get(index));
        }
        return String.join(",", peers);
    }

    private int[] rotatedOrder(int first) {
        int[] order = new int[TOPOLOGY_SIZE];
        for (int index = 0; index < TOPOLOGY_SIZE; index++) {
            order[index] = (first + index) % TOPOLOGY_SIZE;
        }
        return order;
    }

    private void awaitTrue(Check condition, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.evaluate(),
                "Condition was not satisfied before timeout");
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void parkUntil(long deadlineNanos)
            throws InterruptedException {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            LockSupport.parkNanos(Math.min(
                    remaining, TimeUnit.MILLISECONDS.toNanos(100L)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private static void shutdownNow(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stopStateRuntimes(
            List<ControlStatePlaneRuntime> runtimes) {
        if (runtimes.isEmpty()) {
            return;
        }
        ExecutorService stopper = Executors.newFixedThreadPool(
                runtimes.size(), named("xuantong-topology-stop-"));
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (ControlStatePlaneRuntime runtime : runtimes) {
                tasks.add(stopper.submit(() -> {
                    runtime.stop();
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    task.cancel(true);
                }
            }
        } finally {
            shutdownNow(stopper);
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private record ClientHarness(
            int index,
            int[] gatewayOrder,
            CountingSocketDTransport transport,
            AtomicLong observedDecision,
            AtomicLong watchCursor) {

        private ClientHarness {
            gatewayOrder = gatewayOrder.clone();
        }

        @Override
        public int[] gatewayOrder() {
            return gatewayOrder.clone();
        }
    }

    private record StorageSize(
            long walFiles,
            long walBytes,
            long snapshotFiles,
            long snapshotBytes) {
    }

    private record GatewayMetrics(
            String gatewayId,
            boolean started,
            long requestAcceptedTotal,
            long requestCompletedTotal,
            long tenantRateLimitedTotal,
            long overloadedRejectedTotal,
            long stateCallbackRejectedTotal,
            long sessionOpenedTotal,
            long sessionClosedTotal,
            int peakActiveSessions,
            long subscriptionOpenedTotal,
            long subscriptionClosedTotal,
            int peakActiveSubscriptions,
            int peakInFlightRequests,
            int peakWorkQueueDepth,
            int peakStateCallbackQueueDepth) {

        private String toJson() {
            return new StringBuilder(512)
                    .append('{')
                    .append("\"gatewayId\":\"").append(json(gatewayId))
                    .append("\",")
                    .append("\"startedAtReport\":").append(started)
                    .append(',')
                    .append("\"requestAcceptedTotal\":")
                    .append(requestAcceptedTotal).append(',')
                    .append("\"requestCompletedTotal\":")
                    .append(requestCompletedTotal).append(',')
                    .append("\"tenantRateLimitedTotal\":")
                    .append(tenantRateLimitedTotal).append(',')
                    .append("\"overloadedRejectedTotal\":")
                    .append(overloadedRejectedTotal).append(',')
                    .append("\"stateCallbackRejectedTotal\":")
                    .append(stateCallbackRejectedTotal).append(',')
                    .append("\"sessionOpenedTotal\":")
                    .append(sessionOpenedTotal).append(',')
                    .append("\"sessionClosedTotal\":")
                    .append(sessionClosedTotal).append(',')
                    .append("\"peakActiveSessions\":")
                    .append(peakActiveSessions).append(',')
                    .append("\"subscriptionOpenedTotal\":")
                    .append(subscriptionOpenedTotal).append(',')
                    .append("\"subscriptionClosedTotal\":")
                    .append(subscriptionClosedTotal).append(',')
                    .append("\"peakActiveSubscriptions\":")
                    .append(peakActiveSubscriptions).append(',')
                    .append("\"peakInFlightRequests\":")
                    .append(peakInFlightRequests).append(',')
                    .append("\"peakWorkQueueDepth\":")
                    .append(peakWorkQueueDepth).append(',')
                    .append("\"peakStateCallbackQueueDepth\":")
                    .append(peakStateCallbackQueueDepth).append('}')
                    .toString();
        }
    }

    private static final class GatewayHarness {
        private final ControlPlaneGatewayProperties properties;
        private final ControlPlaneGatewayRuntime runtime;
        private final ControlPlaneGatewayServer server;
        private boolean started;

        private GatewayHarness(
                ControlPlaneGatewayProperties properties,
                ControlPlaneGatewayRuntime runtime,
                ControlPlaneGatewayServer server) {
            this.properties = properties;
            this.runtime = runtime;
            this.server = server;
        }

        private void start() throws Exception {
            server.start();
            started = true;
        }

        private void stop() {
            if (started) {
                started = false;
                server.stop();
            }
        }

        private String address() {
            return properties.getHost() + ":" + properties.getPort();
        }

        private GatewayMetrics metrics() {
            return new GatewayMetrics(
                    runtime.gatewayId(),
                    started,
                    runtime.requestAcceptedTotal(),
                    runtime.requestCompletedTotal(),
                    runtime.tenantRequestRateLimitedTotal(),
                    runtime.requestOverloadedRejectedTotal(),
                    runtime.stateCallbackRejectedTotal(),
                    runtime.sessionOpenedTotal(),
                    runtime.sessionClosedTotal(),
                    runtime.peakActiveSessions(),
                    runtime.subscriptionOpenedTotal(),
                    runtime.subscriptionClosedTotal(),
                    runtime.peakActiveSubscriptions(),
                    runtime.peakInFlightRequests(),
                    runtime.peakWorkQueueDepth(),
                    runtime.peakStateCallbackQueueDepth());
        }
    }

    private static final class CountingSocketDTransport
            extends SocketDTransport {
        private final AtomicIntegerArray openAttempts;

        private CountingSocketDTransport(
                ClientIdentity identity,
                ControlPlaneOptions options,
                int gatewayCount) {
            super(identity, options);
            this.openAttempts = new AtomicIntegerArray(gatewayCount);
        }

        @Override
        protected ClientSession openSession(
                int gatewayIndex,
                String url,
                EventListener listener,
                long connectTimeoutMs) throws Exception {
            openAttempts.incrementAndGet(gatewayIndex);
            return super.openSession(
                    gatewayIndex, url, listener, connectTimeoutMs);
        }

        private int openAttempts(int gatewayIndex) {
            return openAttempts.get(gatewayIndex);
        }
    }

    private static final class RatePacer {
        private final int ratePerSecond;
        private final long startedNanos;
        private final AtomicLong issued = new AtomicLong();

        private RatePacer(int ratePerSecond, long startedNanos) {
            this.ratePerSecond = ratePerSecond;
            this.startedNanos = startedNanos;
        }

        private void awaitNext(long workloadDeadlineNanos)
                throws InterruptedException {
            if (ratePerSecond <= 0) {
                return;
            }
            long sequence = issued.getAndIncrement();
            long target = startedNanos + Math.round(
                    sequence * 1_000_000_000D / ratePerSecond);
            parkUntil(Math.min(target, workloadDeadlineNanos));
        }
    }

    private static final class LatencyHistogram {
        private static final long[] UPPER_BOUNDS_MS = {
                1L, 2L, 5L, 10L, 20L, 50L, 100L, 250L,
                500L, 1_000L, 2_000L, 5_000L, Long.MAX_VALUE
        };
        private final LongAdder[] buckets =
                new LongAdder[UPPER_BOUNDS_MS.length];
        private final LongAdder count = new LongAdder();
        private final LongAccumulator maxNanos =
                new LongAccumulator(Math::max, 0L);

        private LatencyHistogram() {
            for (int index = 0; index < buckets.length; index++) {
                buckets[index] = new LongAdder();
            }
        }

        private void record(long latencyNanos) {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, latencyNanos));
            count.increment();
            maxNanos.accumulate(Math.max(0L, latencyNanos));
            for (int index = 0; index < UPPER_BOUNDS_MS.length; index++) {
                if (latencyMs <= UPPER_BOUNDS_MS[index]) {
                    buckets[index].increment();
                    return;
                }
            }
        }

        private long maxNanos() {
            return maxNanos.get();
        }

        private long percentileUpperBoundMs(double percentile) {
            long total = count.sum();
            if (total == 0L) {
                return 0L;
            }
            long required = Math.max(
                    1L, (long) Math.ceil(total * percentile));
            long cumulative = 0L;
            for (int index = 0; index < buckets.length; index++) {
                cumulative += buckets[index].sum();
                if (cumulative >= required) {
                    return UPPER_BOUNDS_MS[index];
                }
            }
            return Long.MAX_VALUE;
        }
    }

    private record LoadProfile(
            int durationSeconds,
            int clients,
            int watchers,
            int fetchConcurrency,
            int fetchRatePerSecond,
            int publishRatePerMinute,
            int payloadBytes,
            boolean failoverEnabled,
            String reportPath,
            String runLabel,
            String buildRevision,
            String buildState) {

        private LoadProfile {
            range("durationSeconds", durationSeconds, 5, 259_200);
            range("clients", clients, 3, 10_000);
            range("watchers", watchers, 0, clients);
            range("fetchConcurrency", fetchConcurrency, 1, 2_048);
            range("fetchRatePerSecond", fetchRatePerSecond, 0, 10_000_000);
            range("publishRatePerMinute", publishRatePerMinute, 0, 60_000);
            range("payloadBytes", payloadBytes, 16, 1_048_576);
            reportPath = reportPath == null ? "" : reportPath.trim();
            runLabel = runLabel == null ? "" : runLabel.trim();
            buildRevision = buildRevision == null
                    ? "unknown" : buildRevision.trim();
            buildState = buildState == null
                    ? "unknown" : buildState.trim();
        }

        private static LoadProfile fromSystemProperties() {
            int clients = integer("clients", 24);
            return new LoadProfile(
                    integer("durationSeconds", 60),
                    clients,
                    integer("watchers", clients),
                    integer("fetchConcurrency", Math.min(32, clients)),
                    integer("fetchRatePerSecond", 1_000),
                    integer("publishRatePerMinute", 12),
                    integer("payloadBytes", 1_024),
                    Boolean.parseBoolean(System.getProperty(
                            PREFIX + "failoverEnabled", "true")),
                    System.getProperty(PREFIX + "reportPath", ""),
                    System.getProperty(PREFIX + "runLabel", ""),
                    System.getProperty(PREFIX + "buildRevision", "unknown"),
                    System.getProperty(PREFIX + "buildState", "unknown"));
        }

        private String toJson() {
            return new StringBuilder(384)
                    .append('{')
                    .append("\"durationSeconds\":")
                    .append(durationSeconds).append(',')
                    .append("\"clients\":").append(clients).append(',')
                    .append("\"watchers\":").append(watchers).append(',')
                    .append("\"fetchConcurrency\":")
                    .append(fetchConcurrency).append(',')
                    .append("\"fetchRatePerSecond\":")
                    .append(fetchRatePerSecond).append(',')
                    .append("\"publishRatePerMinute\":")
                    .append(publishRatePerMinute).append(',')
                    .append("\"payloadBytes\":")
                    .append(payloadBytes).append(',')
                    .append("\"mode\":\"")
                    .append(fetchRatePerSecond == 0
                            ? "capacity-saturation"
                            : "controlled-lossless")
                    .append("\"}")
                    .toString();
        }

        private static int integer(String name, int defaultValue) {
            return Integer.getInteger(PREFIX + name, defaultValue);
        }

        private static void range(
                String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        PREFIX + name + " must be between "
                                + minimum + " and " + maximum);
            }
        }
    }
}
