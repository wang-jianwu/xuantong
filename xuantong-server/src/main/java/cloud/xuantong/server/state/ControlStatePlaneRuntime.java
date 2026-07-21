package cloud.xuantong.server.state;

import cloud.xuantong.common.metrics.FixedLatencyHistogram;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.discovery.management.service.ServiceDefinitionService;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestDispatcher;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
import cloud.xuantong.raft.ratis.RatisGroupCatalog;
import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisGroupRuntimeStatus;
import cloud.xuantong.raft.ratis.RatisStateNode;
import cloud.xuantong.raft.ratis.RatisStateRouter;
import cloud.xuantong.registry.state.ExpireLeaseBatch;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.RegistryStateMachine;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts one compact physical State Node hosting independent Config/Registry Groups. */
@Slf4j
@Component
public final class ControlStatePlaneRuntime {
    @Inject
    private ConfigStatePlaneProperties configProperties;
    @Inject
    private RegistryStatePlaneProperties registryProperties;
    @Inject
    private ControlPlaneRequestDispatcher dispatcher;
    @Inject
    private ControlPlaneGatewayRuntime gatewayRuntime;
    @Inject
    private StateStorageTelemetry stateStorageTelemetry;
    @Inject
    private ServiceDefinitionService serviceDefinitionService;

    private final List<ControlPlaneRequestHandler> registeredHandlers = new ArrayList<>();
    private volatile RatisStateNode node;
    private volatile RatisStateRouter router;
    private ScheduledExecutorService expirationExecutor;
    private final AtomicBoolean expirationProposalInFlight = new AtomicBoolean();

    public ControlStatePlaneRuntime() {
    }

    public ControlStatePlaneRuntime(
            ConfigStatePlaneProperties configProperties,
            ControlPlaneRequestDispatcher dispatcher) {
        this(configProperties, new RegistryStatePlaneProperties(), dispatcher);
    }

    public ControlStatePlaneRuntime(
            ConfigStatePlaneProperties configProperties,
            RegistryStatePlaneProperties registryProperties,
            ControlPlaneRequestDispatcher dispatcher) {
        this.configProperties = configProperties;
        this.registryProperties = registryProperties;
        this.dispatcher = dispatcher;
        this.stateStorageTelemetry = new StateStorageTelemetry(configProperties);
    }

    @Init(index = 1_500)
    public synchronized void start() throws Exception {
        if (!configProperties.isEnabled()) {
            if (registryProperties.isEnabled()) {
                throw new IllegalStateException(
                        "Registry State requires the compact Config State node to be enabled");
            }
            log.warn("State Plane is disabled; Config and Discovery handlers are unavailable. "
                    + "Remove XUANTONG_CONFIG_STATE_ENABLED=false to restore the default local State Plane");
            return;
        }
        if (node != null || router != null) {
            return;
        }

        RatisGroupDefinition configGroup = configProperties.groupDefinition();
        List<RatisGroupDefinition> groups = new ArrayList<>();
        groups.add(configGroup);
        RatisGroupDefinition registryGroup = null;
        if (registryProperties.isEnabled()) {
            registryGroup = registryProperties.groupDefinition(configGroup.peers());
            groups.add(registryGroup);
        }
        RatisGroupCatalog catalog = new RatisGroupCatalog(configGroup, groups);
        RatisStateNode candidateNode = null;
        RatisStateRouter candidateRouter = null;
        List<ControlPlaneRequestHandler> handlers = new ArrayList<>();
        try {
            StateStorageTelemetry storageAdmission = stateStorageTelemetry;
            if (storageAdmission == null) {
                storageAdmission = new StateStorageTelemetry(configProperties);
                stateStorageTelemetry = storageAdmission;
            }
            candidateNode = new RatisStateNode(
                    configProperties.nodeOptions(configGroup),
                    catalog,
                    groupId -> stateMachine(groupId));
            candidateNode.start();
            if (registryGroup != null && !configProperties.joinExisting()) {
                candidateNode.addGroup(registryGroup);
            }
            if (!configProperties.joinExisting()) {
                candidateNode.awaitReady(
                        groups.stream().map(RatisGroupDefinition::groupId).toList(),
                        configProperties.startupReadyTimeout());
            }
            Map<StateGroupId, String> initialLeaderIds = new LinkedHashMap<>();
            var hostedGroups = candidateNode.hostedGroups();
            for (RatisGroupDefinition group : groups) {
                if (!hostedGroups.contains(group.groupId())) {
                    continue;
                }
                String leaderId = candidateNode.groupRuntimeStatus(
                        group.groupId()).leaderId();
                if (!leaderId.isBlank()) {
                    initialLeaderIds.put(group.groupId(), leaderId);
                }
            }
            candidateRouter = new RatisStateRouter(
                    groups,
                    configProperties.requestTimeout(),
                    configProperties.clientMaxAttempts(),
                    storageAdmission,
                    initialLeaderIds);
            ControlPlaneStateExecutor stateExecutor =
                    new ControlPlaneStateExecutor(candidateRouter);
            addConfigHandlers(handlers, stateExecutor);
            if (registryGroup != null) {
                addRegistryHandlers(handlers, stateExecutor);
            }
            for (ControlPlaneRequestHandler handler : handlers) {
                dispatcher.register(handler);
            }
            registeredHandlers.addAll(handlers);
            node = candidateNode;
            router = candidateRouter;
            if (registryGroup != null) {
                startExpirationCoordinator(
                        candidateNode, candidateRouter, registryGroup.groupId());
            }
            log.info("State Plane started: nodeId={}, groups={}, peers={}, startupMode={}, storage={}",
                    candidateNode.nodeId(),
                    groups.stream().map(group -> group.groupId().canonicalName()).toList(),
                    configGroup.peers().size(),
                    configProperties.joinExisting() ? "JOIN_EXISTING" : "BOOTSTRAP_OR_RECOVER",
                    configProperties.nodeOptions(configGroup).storageDirectory());
        } catch (Exception e) {
            stopExpirationCoordinator();
            for (ControlPlaneRequestHandler handler : handlers) {
                dispatcher.unregister(handler);
            }
            closeQuietly(candidateRouter);
            closeQuietly(candidateNode);
            throw e;
        }
    }

    private cloud.xuantong.state.api.StateMachine stateMachine(StateGroupId groupId) {
        return switch (groupId.type()) {
            case CONFIG -> new ConfigStateMachine(groupId, configProperties.stateOptions());
            case REGISTRY -> new RegistryStateMachine(groupId, registryProperties.stateOptions());
            default -> throw new IllegalArgumentException(
                    "No State Machine configured for " + groupId);
        };
    }

    private void addConfigHandlers(
            List<ControlPlaneRequestHandler> handlers,
            ControlPlaneStateExecutor stateExecutor) {
        handlers.add(new ConfigFetchControlPlaneHandler(stateExecutor, gatewayRuntime));
        handlers.add(new ConfigSnapshotControlPlaneHandler(stateExecutor));
        handlers.add(new ConfigWatchBatchControlPlaneHandler(stateExecutor));
    }

    private void addRegistryHandlers(
            List<ControlPlaneRequestHandler> handlers,
            ControlPlaneStateExecutor stateExecutor) {
        handlers.add(new DiscoveryMutationControlPlaneHandler(
                stateExecutor,
                DiscoveryMutationControlPlaneHandler.Operation.REGISTER,
                serviceDefinitionService));
        handlers.add(new DiscoveryMutationControlPlaneHandler(
                stateExecutor, DiscoveryMutationControlPlaneHandler.Operation.RENEW_BATCH));
        handlers.add(new DiscoveryMutationControlPlaneHandler(
                stateExecutor, DiscoveryMutationControlPlaneHandler.Operation.DEREGISTER));
        handlers.add(new DiscoveryMutationControlPlaneHandler(
                stateExecutor, DiscoveryMutationControlPlaneHandler.Operation.TAKEOVER));
        handlers.add(new DiscoverySnapshotControlPlaneHandler(stateExecutor));
        handlers.add(new DiscoveryWatchBatchControlPlaneHandler(stateExecutor));
        handlers.add(new DiscoveryLeaseStateControlPlaneHandler(stateExecutor));
        handlers.add(new DiscoveryResolveOperationControlPlaneHandler(stateExecutor));
    }

    private void startExpirationCoordinator(
            RatisStateNode stateNode,
            RatisStateRouter stateRouter,
            StateGroupId registryGroupId) {
        long intervalMs = registryProperties.expirationInterval().toMillis();
        expirationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-registry-expirer");
            thread.setDaemon(true);
            return thread;
        });
        RegistryActor actor = RegistryActor.system(configProperties.localNodeId());
        expirationExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (!stateNode.isLeaderReady(registryGroupId)
                        || !expirationProposalInFlight.compareAndSet(false, true)) {
                    return;
                }
                stateRouter.submit(RegistryStateCodec.mutationCommand(
                        registryGroupId,
                        "expire:" + UUID.randomUUID(),
                        new ExpireLeaseBatch(
                                actor,
                                registryProperties.expirationBatchSize(),
                                System.currentTimeMillis())))
                        .whenComplete((ignored, failure) -> {
                            expirationProposalInFlight.set(false);
                            if (failure != null) {
                                log.debug("Registry expiration proposal failed", failure);
                            }
                        });
            } catch (Exception e) {
                expirationProposalInFlight.set(false);
                log.debug("Registry expiration scheduling failed", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Destroy
    public synchronized void stop() {
        stopExpirationCoordinator();
        for (int i = registeredHandlers.size() - 1; i >= 0; i--) {
            dispatcher.unregister(registeredHandlers.get(i));
        }
        registeredHandlers.clear();
        RatisStateRouter currentRouter = router;
        router = null;
        RatisStateNode currentNode = node;
        node = null;
        closeQuietly(currentRouter);
        closeQuietly(currentNode);
        if (currentRouter != null || currentNode != null) {
            log.info("State Plane stopped");
        }
    }

    private void stopExpirationCoordinator() {
        ScheduledExecutorService executor = expirationExecutor;
        expirationExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        expirationProposalInFlight.set(false);
    }

    public boolean isRunning() {
        RatisStateNode current = node;
        RatisStateRouter currentRouter = router;
        return current != null && currentRouter != null
                && current.isReady(currentRouter.groups());
    }

    public StateClient stateClient() {
        RatisStateRouter current = router;
        if (current == null) {
            throw new IllegalStateException("State Plane is not running");
        }
        return current;
    }

    public FixedLatencyHistogram.Snapshot stateApplyLatencySnapshot() {
        RatisStateRouter current = router;
        return current == null ? null : current.applyLatencySnapshot();
    }

    /** Local, read-only Raft status used by monitoring and topology acceptance tests. */
    public RatisGroupRuntimeStatus stateGroupRuntimeStatus(StateGroupId groupId)
            throws java.io.IOException {
        RatisStateNode current = node;
        if (current == null) {
            throw new IllegalStateException("State Plane is not running");
        }
        return current.groupRuntimeStatus(groupId);
    }

    public synchronized void refreshTopology(List<cloud.xuantong.raft.ratis.RatisPeerDefinition> peers)
            throws java.io.IOException {
        RatisStateRouter current = router;
        if (current == null) {
            throw new IllegalStateException("State Plane is not running");
        }
        RatisGroupDefinition configGroup = new RatisGroupDefinition(
                configProperties.stateGroupId(), peers);
        List<RatisGroupDefinition> groups = new ArrayList<>();
        groups.add(configGroup);
        if (registryProperties.isEnabled()) {
            groups.add(registryProperties.groupDefinition(configGroup.peers()));
        }
        current.replaceGroups(groups);
        log.info("State client topology refreshed: peers={}",
                peers.stream().map(cloud.xuantong.raft.ratis.RatisPeerDefinition::nodeId)
                        .toList());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Failed to close State Plane component", e);
        }
    }
}
