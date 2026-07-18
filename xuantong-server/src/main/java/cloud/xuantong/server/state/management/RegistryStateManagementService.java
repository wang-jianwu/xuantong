package cloud.xuantong.server.state.management;

import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.registry.state.ActivateServiceDefinition;
import cloud.xuantong.registry.state.DeleteServiceDefinition;
import cloud.xuantong.registry.state.EvictLease;
import cloud.xuantong.registry.state.GetServiceLifecycleRequest;
import cloud.xuantong.registry.state.InstanceKey;
import cloud.xuantong.registry.state.LeaseReference;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryInstance;
import cloud.xuantong.registry.state.RegistryMutationResult;
import cloud.xuantong.registry.state.RegistryOverview;
import cloud.xuantong.registry.state.RegistrySnapshot;
import cloud.xuantong.registry.state.RegistrySnapshotRequest;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.ServiceKey;
import cloud.xuantong.registry.state.ServiceLifecycle;
import cloud.xuantong.registry.state.ServiceLifecycleState;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateClient;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Read/eviction management facade backed only by the authoritative Registry Group. */
@Component
public final class RegistryStateManagementService {
    private static final RegistryActor LIFECYCLE_ACTOR = new RegistryActor(
            "management", "service-lifecycle", "xuantong-admin");

    @Inject
    private ControlStatePlaneRuntime runtime;
    @Inject
    private RegistryStatePlaneProperties properties;
    private StateClient directStateClient;

    public RegistryStateManagementService() {
    }

    RegistryStateManagementService(
            ControlStatePlaneRuntime runtime,
            RegistryStatePlaneProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    RegistryStateManagementService(
            StateClient stateClient,
            RegistryStatePlaneProperties properties) {
        this.runtime = new ControlStatePlaneRuntime();
        this.directStateClient = stateClient;
        this.properties = properties;
    }

    public boolean available() {
        return properties.isEnabled()
                && (directStateClient != null || runtime.isRunning());
    }

    public cloud.xuantong.discovery.management.model.ServiceSnapshot snapshot(
            String namespace,
            String group,
            String serviceName,
            boolean onlyAvailable) {
        RegistrySnapshot snapshot = snapshot(namespace, group, List.of(serviceName));
        List<cloud.xuantong.discovery.management.model.ServiceInstance> instances =
                snapshot.instances().stream()
                        .filter(instance -> !onlyAvailable
                                || instance.registration().enabled())
                        .map(this::toManagementInstance)
                        .toList();
        return new cloud.xuantong.discovery.management.model.ServiceSnapshot(
                cloud.xuantong.resource.model.ServiceKey.of(
                        namespace, group, serviceName),
                snapshot.registryRevision(),
                instances);
    }

    public long activateServiceDefinition(
            String namespace,
            String group,
            String serviceName,
            String operationId) {
        if (!properties.isEnabled()) {
            return 0L;
        }
        requireAvailable();
        ServiceKey key = new ServiceKey(namespace, group, serviceName);
        ServiceLifecycleState current = serviceLifecycle(key);
        if (current.found() && current.lifecycle().active()) {
            return current.lifecycle().generation();
        }
        long previousGeneration = current.found()
                ? current.lifecycle().generation() : 0L;
        ApplyResult result = join(stateClient().submit(
                RegistryStateCodec.mutationCommand(
                        properties.stateGroupId(),
                        requiredOperationId(operationId),
                        new ActivateServiceDefinition(
                                LIFECYCLE_ACTOR,
                                key,
                                previousGeneration,
                                System.currentTimeMillis()))));
        RegistryMutationResult mutation = requireLifecycleMutation(
                result, previousGeneration);
        if (mutation.services().size() != 1
                || !mutation.services().getFirst().active()) {
            throw new IllegalStateException(
                    "Registry State returned no activated service lifecycle");
        }
        return mutation.services().getFirst().generation();
    }

    public void deleteServiceDefinition(
            String namespace,
            String group,
            String serviceName,
            long expectedGeneration,
            String operationId) {
        if (!properties.isEnabled()) {
            return;
        }
        requireAvailable();
        ServiceKey key = new ServiceKey(namespace, group, serviceName);
        ServiceLifecycleState current = serviceLifecycle(key);
        if (!current.found() || !current.lifecycle().active()) {
            return;
        }
        ServiceLifecycle lifecycle = current.lifecycle();
        if (expectedGeneration > 0 && expectedGeneration != lifecycle.generation()) {
            throw new RegistryLifecycleMutationException(
                    RegistryLifecycleMutationException.Reason.REJECTED,
                    lifecycle.generation(),
                    "Service definition generation is stale: expected="
                            + expectedGeneration + ", current=" + lifecycle.generation());
        }
        ApplyResult result = join(stateClient().submit(
                RegistryStateCodec.mutationCommand(
                        properties.stateGroupId(),
                        requiredOperationId(operationId),
                        new DeleteServiceDefinition(
                                LIFECYCLE_ACTOR,
                                key,
                                lifecycle.generation(),
                                System.currentTimeMillis()))));
        RegistryMutationResult mutation = requireLifecycleMutation(
                result, lifecycle.generation());
        if (mutation.services().size() != 1
                || mutation.services().getFirst().active()) {
            throw new IllegalStateException(
                    "Registry State returned no deleted service lifecycle");
        }
    }

    public ServiceLifecycleState serviceLifecycle(
            String namespace, String group, String serviceName) {
        requireAvailable();
        return serviceLifecycle(new ServiceKey(namespace, group, serviceName));
    }

    public RegistryOverview overview() {
        requireAvailable();
        QueryResult result = join(stateClient().query(
                RegistryStateCodec.overviewQuery(
                        properties.stateGroupId(), ReadOptions.linearizable())));
        if (!RegistryStateCodec.RESULT_OVERVIEW.equals(result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Registry State overview result: " + result.resultType());
        }
        try {
            return RegistryStateCodec.decodeOverview(result.payload());
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Registry State overview result", e);
        }
    }

    public boolean evict(
            String namespace,
            String group,
            String serviceName,
            String instanceId,
            String operator) {
        requireAvailable();
        ServiceKey service = new ServiceKey(namespace, group, serviceName);
        InstanceKey key = new InstanceKey(service, instanceId);
        RegistryInstance instance = snapshot(namespace, group, List.of(serviceName))
                .instances().stream()
                .filter(value -> key.equals(value.instanceKey()))
                .findFirst()
                .orElse(null);
        if (instance == null) {
            return false;
        }
        RegistryActor actor = new RegistryActor(
                "management",
                "admin:" + requiredOperator(operator),
                "xuantong-admin");
        ApplyResult result = join(stateClient().submit(
                RegistryStateCodec.mutationCommand(
                        properties.stateGroupId(),
                        "evict:" + UUID.randomUUID(),
                        new EvictLease(
                                actor,
                                new LeaseReference(
                                        key,
                                        instance.leaseId(),
                                        instance.leaseEpoch(),
                                        instance.recoveryEpoch()),
                                System.currentTimeMillis()))));
        if (result.status() == ApplyStatus.REJECTED) {
            try {
                throw new IllegalStateException(
                        RegistryStateCodec.decodeMutationError(result.payload()).message());
            } catch (IOException e) {
                throw new IllegalStateException("Registry eviction was rejected", e);
            }
        }
        return true;
    }

    private RegistrySnapshot snapshot(
            String namespace, String group, List<String> serviceNames) {
        requireAvailable();
        QueryResult result = join(stateClient().query(
                RegistryStateCodec.snapshotQuery(
                        properties.stateGroupId(),
                        new RegistrySnapshotRequest(namespace, group, serviceNames),
                        ReadOptions.linearizable())));
        if (!RegistryStateCodec.RESULT_SNAPSHOT.equals(result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Registry State snapshot result: " + result.resultType());
        }
        try {
            return RegistryStateCodec.decodeSnapshot(result.payload());
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Registry State snapshot result", e);
        }
    }

    private ServiceLifecycleState serviceLifecycle(ServiceKey key) {
        QueryResult result = join(stateClient().query(
                RegistryStateCodec.serviceLifecycleQuery(
                        properties.stateGroupId(),
                        new GetServiceLifecycleRequest(key),
                        ReadOptions.linearizable())));
        if (!RegistryStateCodec.RESULT_SERVICE_LIFECYCLE.equals(result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Registry service lifecycle result: "
                            + result.resultType());
        }
        try {
            return RegistryStateCodec.decodeServiceLifecycleState(result.payload());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Malformed Registry service lifecycle result", e);
        }
    }

    private RegistryMutationResult requireLifecycleMutation(
            ApplyResult result, long serviceGeneration) {
        try {
            if (result.status() == ApplyStatus.REJECTED) {
                cloud.xuantong.registry.state.RegistryMutationError error =
                        RegistryStateCodec.decodeMutationError(result.payload());
                RegistryLifecycleMutationException.Reason reason =
                        "SERVICE_HAS_ACTIVE_LEASES".equals(error.code())
                                ? RegistryLifecycleMutationException.Reason.ACTIVE_LEASES
                                : RegistryLifecycleMutationException.Reason.REJECTED;
                throw new RegistryLifecycleMutationException(
                        reason, serviceGeneration, error.message());
            }
            if (!RegistryStateCodec.RESULT_MUTATION.equals(result.resultType())) {
                throw new IllegalStateException(
                        "Unexpected Registry lifecycle mutation result: "
                                + result.resultType());
            }
            return RegistryStateCodec.decodeMutationResult(result.payload());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Malformed Registry lifecycle mutation result", e);
        }
    }

    private cloud.xuantong.discovery.management.model.ServiceInstance toManagementInstance(
            RegistryInstance source) {
        cloud.xuantong.discovery.management.model.ServiceInstance target =
                new cloud.xuantong.discovery.management.model.ServiceInstance();
        target.setNamespaceId(source.instanceKey().service().namespace());
        target.setGroupName(source.instanceKey().service().group());
        target.setServiceName(source.instanceKey().service().serviceName());
        target.setInstanceId(source.instanceKey().instanceId());
        target.setServiceGeneration(source.registration().serviceGeneration());
        target.setLeaseId(source.leaseId());
        target.setLeaseEpoch(source.leaseEpoch());
        target.setRecoveryEpoch(source.recoveryEpoch());
        target.setRenewSequence(source.renewSequence());
        target.setLeaseStartedAt(source.registeredAtEpochMs());
        target.setExpiresAt(source.expiresAtEpochMs());
        target.setIp(source.registration().ip());
        target.setPort(source.registration().port());
        target.setWeight(source.registration().weight());
        target.setHealthy(true);
        target.setEnabled(source.registration().enabled());
        target.setMetadata(source.registration().metadata());
        target.setOwnerNodeId(source.ownerClientInstanceId());
        target.setRegisteredAt(source.registeredAtEpochMs());
        target.setLastHeartbeatAt(source.lastRenewedAtEpochMs());
        return target;
    }

    private void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException(
                    "Registry State Plane is disabled or unavailable");
        }
    }

    private StateClient stateClient() {
        return directStateClient == null ? runtime.stateClient() : directStateClient;
    }

    private String requiredOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "system";
        }
        String normalized = operator.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private String requiredOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        String normalized = operationId.trim();
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("operationId is too long");
        }
        return normalized;
    }

    private <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Registry State access failed", cause);
        }
    }
}
