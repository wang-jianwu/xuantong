package cloud.xuantong.gateway.socketd;

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
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevisionType;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only child process hosting the production State/Gateway runtime path. */
public final class ControlPlaneSplitProcessNodeMain {
    private static final StateGroupId CONFIG_GROUP =
            StateGroupId.config("config-default");
    private static final ConfigKey CONFIG_KEY = new ConfigKey(
            "public", "DEFAULT_GROUP", "split-topology.payload");

    private ControlPlaneSplitProcessNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException(
                    "Expected nodeId, peers, stateDir, gatewayPort, controlDir, "
                            + "maxClients, fetchConcurrency and startupTimeoutSeconds");
        }
        String nodeId = args[0];
        String peers = args[1];
        Path stateDirectory = Path.of(args[2]).toAbsolutePath().normalize();
        int gatewayPort = Integer.parseInt(args[3]);
        Path controlDirectory = Path.of(args[4]).toAbsolutePath().normalize();
        int maxClients = Integer.parseInt(args[5]);
        int fetchConcurrency = Integer.parseInt(args[6]);
        int startupTimeoutSeconds = Integer.parseInt(args[7]);
        Files.createDirectories(controlDirectory);

        ControlPlaneRequestDispatcher dispatcher =
                new ControlPlaneRequestDispatcher();
        ConfigStatePlaneProperties stateProperties =
                new ConfigStatePlaneProperties(
                        true,
                        nodeId,
                        CONFIG_GROUP.value(),
                        peers,
                        stateDirectory,
                        false,
                        Duration.ofSeconds(startupTimeoutSeconds));
        ControlStatePlaneRuntime stateRuntime =
                new ControlStatePlaneRuntime(stateProperties, dispatcher);
        ControlPlaneGatewayProperties gatewayProperties =
                new ControlPlaneGatewayProperties(
                        "127.0.0.1",
                        gatewayPort,
                        "cluster-split-process-topology-test",
                        "gateway-" + nodeId,
                        1L,
                        10_000L,
                        Math.max(256,
                                Math.max(fetchConcurrency * 8, maxClients * 2)),
                        Math.min(64, Math.max(4,
                                Runtime.getRuntime().availableProcessors())),
                        Math.max(4_096, fetchConcurrency * 32),
                        100_000_000,
                        100_000_000,
                        5_000L,
                        ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime gatewayRuntime =
                new ControlPlaneGatewayRuntime(gatewayProperties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(
                        gatewayProperties, gatewayRuntime, dispatcher));
        ControlPlaneGatewayServer gatewayServer =
                new ControlPlaneGatewayServer(
                        gatewayProperties, gatewayRuntime, router, null);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                gatewayServer.stop();
                stateRuntime.stop();
            }
        };
        Thread shutdownHook = new Thread(
                close, "xuantong-split-node-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            stateRuntime.start();
            gatewayServer.start();
            writeProperties(
                    controlDirectory.resolve("ready.properties"),
                    status(
                            nodeId,
                            gatewayPort,
                            gatewayRuntime,
                            stateRuntime,
                            "ready"));
            commandLoop(
                    nodeId,
                    gatewayPort,
                    controlDirectory,
                    gatewayRuntime,
                    stateRuntime);
        } finally {
            close.run();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already owns the hook.
            }
        }
    }

    private static void commandLoop(
            String nodeId,
            int gatewayPort,
            Path controlDirectory,
            ControlPlaneGatewayRuntime gatewayRuntime,
            ControlStatePlaneRuntime stateRuntime) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        while (!stop.get()) {
            List<Path> requests;
            try (var paths = Files.list(controlDirectory)) {
                requests = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .endsWith(".request"))
                        .sorted()
                        .toList();
            }
            if (requests.isEmpty()) {
                Thread.sleep(20L);
                continue;
            }
            for (Path requestPath : requests) {
                Properties request = readProperties(requestPath);
                String name = requestPath.getFileName().toString();
                String requestId = name.substring(
                        0, name.length() - ".request".length());
                Path responsePath = controlDirectory.resolve(
                        requestId + ".response");
                Properties response = new Properties();
                try {
                    String action = required(request, "action");
                    switch (action) {
                        case "publish" -> publish(
                                request, response, stateRuntime);
                        case "status" -> response.putAll(status(
                                nodeId,
                                gatewayPort,
                                gatewayRuntime,
                                stateRuntime,
                                "running"));
                        case "shutdown" -> {
                            response.setProperty("status", "accepted");
                            stop.set(true);
                        }
                        default -> throw new IllegalArgumentException(
                                "Unsupported action: " + action);
                    }
                } catch (Throwable e) {
                    response.setProperty("status", "error");
                    response.setProperty("errorType", e.getClass().getName());
                    response.setProperty("errorMessage",
                            String.valueOf(e.getMessage()));
                }
                writeProperties(responsePath, response);
                Files.deleteIfExists(requestPath);
                if (stop.get()) {
                    return;
                }
            }
        }
    }

    private static void publish(
            Properties request,
            Properties response,
            ControlStatePlaneRuntime stateRuntime) throws Exception {
        long revision = Long.parseLong(required(request, "revision"));
        int payloadBytes = Integer.parseInt(required(request, "payloadBytes"));
        ApplyResult applied = submitEventually(
                stateRuntime,
                mutation(revision, payload(revision, payloadBytes)));
        response.setProperty("status", applied.status().name());
        response.setProperty("decisionRevision", Long.toString(revision));
        response.setProperty("eventRevision", Long.toString(revision(
                applied, StateRevisionType.CONFIG_EVENT)));
        response.setProperty("appliedIndex",
                Long.toString(applied.appliedIndex()));
    }

    private static Properties status(
            String nodeId,
            int gatewayPort,
            ControlPlaneGatewayRuntime gatewayRuntime,
            ControlStatePlaneRuntime stateRuntime,
            String lifecycle) throws IOException {
        RatisGroupRuntimeStatus state =
                stateRuntime.stateGroupRuntimeStatus(CONFIG_GROUP);
        Properties result = new Properties();
        result.setProperty("status", "ok");
        result.setProperty("lifecycle", lifecycle);
        result.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        result.setProperty("nodeId", nodeId);
        result.setProperty("gatewayId", gatewayRuntime.gatewayId());
        result.setProperty("gatewayPort", Integer.toString(gatewayPort));
        result.setProperty("activeSessions",
                Integer.toString(gatewayRuntime.activeSessions()));
        result.setProperty("activeSubscriptions",
                Integer.toString(gatewayRuntime.activeSubscriptions()));
        result.setProperty("pendingWatchAcknowledgements",
                Integer.toString(gatewayRuntime.pendingWatchAcknowledgements()));
        result.setProperty("inFlightRequests",
                Integer.toString(gatewayRuntime.inFlightRequests()));
        result.setProperty("requestAcceptedTotal",
                Long.toString(gatewayRuntime.requestAcceptedTotal()));
        result.setProperty("requestCompletedTotal",
                Long.toString(gatewayRuntime.requestCompletedTotal()));
        result.setProperty("tenantRateLimitedTotal",
                Long.toString(gatewayRuntime.tenantRequestRateLimitedTotal()));
        result.setProperty("overloadedRejectedTotal",
                Long.toString(gatewayRuntime.requestOverloadedRejectedTotal()));
        result.setProperty("stateCallbackRejectedTotal",
                Long.toString(gatewayRuntime.stateCallbackRejectedTotal()));
        result.setProperty("sessionOpenedTotal",
                Long.toString(gatewayRuntime.sessionOpenedTotal()));
        result.setProperty("sessionClosedTotal",
                Long.toString(gatewayRuntime.sessionClosedTotal()));
        result.setProperty("peakActiveSessions",
                Integer.toString(gatewayRuntime.peakActiveSessions()));
        result.setProperty("subscriptionOpenedTotal",
                Long.toString(gatewayRuntime.subscriptionOpenedTotal()));
        result.setProperty("subscriptionClosedTotal",
                Long.toString(gatewayRuntime.subscriptionClosedTotal()));
        result.setProperty("peakActiveSubscriptions",
                Integer.toString(gatewayRuntime.peakActiveSubscriptions()));
        result.setProperty("peakInFlightRequests",
                Integer.toString(gatewayRuntime.peakInFlightRequests()));
        result.setProperty("peakWorkQueueDepth",
                Integer.toString(gatewayRuntime.peakWorkQueueDepth()));
        result.setProperty("peakStateCallbackQueueDepth",
                Integer.toString(gatewayRuntime.peakStateCallbackQueueDepth()));
        result.setProperty("workQueueDepth",
                Integer.toString(gatewayRuntime.workQueueDepth()));
        result.setProperty("stateCallbackQueueDepth",
                Integer.toString(gatewayRuntime.stateCallbackQueueDepth()));
        appendJvmStatus(result);
        result.setProperty("stateAlive", Boolean.toString(state.alive()));
        result.setProperty("stateLeader", Boolean.toString(state.leader()));
        result.setProperty("stateLeaderReady",
                Boolean.toString(state.leaderReady()));
        result.setProperty("stateLeaderId", state.leaderId());
        result.setProperty("stateCurrentTerm",
                Long.toString(state.currentTerm()));
        result.setProperty("stateLastCommittedIndex",
                Long.toString(state.lastCommittedIndex()));
        result.setProperty("stateLastAppliedIndex",
                Long.toString(state.lastAppliedIndex()));
        return result;
    }

    private static void appendJvmStatus(Properties result) {
        long heapAfterLastGcBytes = 0L;
        int heapAfterLastGcPoolCount = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP
                    || pool.getCollectionUsage() == null) {
                continue;
            }
            heapAfterLastGcBytes += Math.max(
                    0L, pool.getCollectionUsage().getUsed());
            heapAfterLastGcPoolCount++;
        }
        long gcCollectionCount = 0L;
        long gcCollectionTimeMs = 0L;
        for (GarbageCollectorMXBean collector
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCollectionCount += Math.max(0L, collector.getCollectionCount());
            gcCollectionTimeMs += Math.max(0L, collector.getCollectionTime());
        }
        long directBufferCount = 0L;
        long directBufferMemoryUsedBytes = 0L;
        long directBufferTotalCapacityBytes = 0L;
        long mappedBufferCount = 0L;
        long mappedBufferMemoryUsedBytes = 0L;
        long mappedBufferTotalCapacityBytes = 0L;
        for (BufferPoolMXBean pool
                : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String name = pool.getName().toLowerCase(Locale.ROOT);
            if ("direct".equals(name)) {
                directBufferCount += Math.max(0L, pool.getCount());
                directBufferMemoryUsedBytes += Math.max(
                        0L, pool.getMemoryUsed());
                directBufferTotalCapacityBytes += Math.max(
                        0L, pool.getTotalCapacity());
            } else if (name.startsWith("mapped")) {
                mappedBufferCount += Math.max(0L, pool.getCount());
                mappedBufferMemoryUsedBytes += Math.max(
                        0L, pool.getMemoryUsed());
                mappedBufferTotalCapacityBytes += Math.max(
                        0L, pool.getTotalCapacity());
            }
        }
        result.setProperty("heapUsedBytes", Long.toString(
                ManagementFactory.getMemoryMXBean()
                        .getHeapMemoryUsage().getUsed()));
        result.setProperty("nonHeapUsedBytes", Long.toString(
                ManagementFactory.getMemoryMXBean()
                        .getNonHeapMemoryUsage().getUsed()));
        result.setProperty("heapAfterLastGcBytes",
                Long.toString(heapAfterLastGcBytes));
        result.setProperty("heapAfterLastGcPoolCount",
                Integer.toString(heapAfterLastGcPoolCount));
        result.setProperty("gcCollectionCount",
                Long.toString(gcCollectionCount));
        result.setProperty("gcCollectionTimeMs",
                Long.toString(gcCollectionTimeMs));
        result.setProperty("directBufferCount",
                Long.toString(directBufferCount));
        result.setProperty("directBufferMemoryUsedBytes",
                Long.toString(directBufferMemoryUsedBytes));
        result.setProperty("directBufferTotalCapacityBytes",
                Long.toString(directBufferTotalCapacityBytes));
        result.setProperty("mappedBufferCount",
                Long.toString(mappedBufferCount));
        result.setProperty("mappedBufferMemoryUsedBytes",
                Long.toString(mappedBufferMemoryUsedBytes));
        result.setProperty("mappedBufferTotalCapacityBytes",
                Long.toString(mappedBufferTotalCapacityBytes));
        result.setProperty("liveThreads", Integer.toString(
                ManagementFactory.getThreadMXBean().getThreadCount()));
    }

    private static StateCommand mutation(long revision, byte[] value) {
        return ConfigStateCodec.mutationCommand(
                CONFIG_GROUP,
                "split-topology-publish-" + revision,
                new ConfigMutation(
                        new ConfigActor("default", "split-topology-load"),
                        CONFIG_KEY,
                        revision - 1L,
                        ConfigContentDraft.inline(
                                "text", Math.toIntExact(revision), value),
                        ConfigContentReference.newContent(),
                        List.of()));
    }

    private static byte[] payload(long revision, int size) {
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

    private static ApplyResult submitEventually(
            ControlStatePlaneRuntime runtime,
            StateCommand command) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(20).toNanos();
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
                ? new IllegalStateException("State mutation did not complete")
                : last;
    }

    private static long revision(
            ApplyResult result, StateRevisionType type) {
        return result.revisions().stream()
                .filter(value -> value.type() == type)
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void writeProperties(
            Path target, Properties properties) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + UUID.randomUUID());
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, null);
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
