package cloud.xuantong.server.state;

import cloud.xuantong.discovery.management.service.ServiceDefinitionService;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoveryDeregisterRequest;
import cloud.xuantong.protocol.v2.DiscoveryLeaseReference;
import cloud.xuantong.protocol.v2.DiscoveryRegisterRequest;
import cloud.xuantong.protocol.v2.DiscoveryRenewBatchRequest;
import cloud.xuantong.protocol.v2.DiscoveryTakeoverRequest;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.registry.state.DeregisterLease;
import cloud.xuantong.registry.state.InstanceKey;
import cloud.xuantong.registry.state.LeaseReference;
import cloud.xuantong.registry.state.LeaseRenewal;
import cloud.xuantong.registry.state.RegisterLease;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryMutation;
import cloud.xuantong.registry.state.RegistryMutationResult;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.RenewLeaseBatch;
import cloud.xuantong.registry.state.ServiceLifecycle;
import cloud.xuantong.registry.state.ServiceRegistration;
import cloud.xuantong.registry.state.TakeoverLease;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateGroupId;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

@Slf4j
final class DiscoveryMutationControlPlaneHandler implements ControlPlaneRequestHandler {
    enum Operation {
        REGISTER(ControlPlaneProtocol.DISCOVERY_REGISTER,
                ControlPlaneProtocol.DISCOVERY_REGISTER_REQUEST_TYPE),
        RENEW_BATCH(ControlPlaneProtocol.DISCOVERY_RENEW_BATCH,
                ControlPlaneProtocol.DISCOVERY_RENEW_BATCH_REQUEST_TYPE),
        DEREGISTER(ControlPlaneProtocol.DISCOVERY_DEREGISTER,
                ControlPlaneProtocol.DISCOVERY_DEREGISTER_REQUEST_TYPE),
        TAKEOVER(ControlPlaneProtocol.DISCOVERY_TAKEOVER,
                ControlPlaneProtocol.DISCOVERY_TAKEOVER_REQUEST_TYPE);

        private final String event;
        private final String payloadType;

        Operation(String event, String payloadType) {
            this.event = event;
            this.payloadType = payloadType;
        }
    }

    private final ControlPlaneStateExecutor stateExecutor;
    private final Operation operation;
    private final ServiceDefinitionService serviceDefinitions;

    DiscoveryMutationControlPlaneHandler(
            ControlPlaneStateExecutor stateExecutor, Operation operation) {
        this(stateExecutor, operation, null);
    }

    DiscoveryMutationControlPlaneHandler(
            ControlPlaneStateExecutor stateExecutor,
            Operation operation,
            ServiceDefinitionService serviceDefinitions) {
        this.stateExecutor = stateExecutor;
        this.operation = operation;
        this.serviceDefinitions = serviceDefinitions;
    }

    @Override
    public String event() {
        return operation.event;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!operation.payloadType.equals(request.getPayloadType())) {
                throw new IllegalArgumentException(
                        "payloadType must be " + operation.payloadType);
            }
            if (request.getOperationId().isBlank()) {
                throw new IllegalArgumentException(
                        "discovery mutations require operationId");
            }
            StateGroupId groupId = RegistryControlPlaneSupport.requireRegistryGroup(request);
            RegistryActor actor = new RegistryActor(
                    context.tenant(),
                    context.clientInstanceId(),
                    context.applicationName());
            RegistryMutation mutation = switch (operation) {
                case REGISTER -> register(context, actor, request);
                case RENEW_BATCH -> renew(context, actor, request);
                case DEREGISTER -> deregister(context, actor, request);
                case TAKEOVER -> takeover(context, actor, request);
            };
            return stateExecutor.submit(
                            RegistryStateCodec.mutationCommand(
                                    groupId, request.getOperationId(), mutation),
                            context)
                    .thenApply(result -> mutationReply(context, mutation, result));
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            return RegistryControlPlaneSupport.invalid(e.getMessage());
        }
    }

    private ControlPlaneReply mutationReply(
            ControlPlaneRequestContext context,
            RegistryMutation mutation,
            ApplyResult result) {
        if (operation == Operation.REGISTER
                && serviceDefinitions != null
                && result.status() != ApplyStatus.REJECTED
                && RegistryStateCodec.RESULT_MUTATION.equals(result.resultType())) {
            projectRegisteredService(context, (RegisterLease) mutation, result);
        }
        return RegistryControlPlaneSupport.mutationReply(result);
    }

    private void projectRegisteredService(
            ControlPlaneRequestContext context,
            RegisterLease register,
            ApplyResult result) {
        var key = register.registration().instanceKey().service();
        try {
            RegistryMutationResult mutation = RegistryStateCodec.decodeMutationResult(
                    result.payload());
            long generation = mutation.services().stream()
                    .filter(ServiceLifecycle::active)
                    .filter(value -> value.serviceKey().equals(key))
                    .mapToLong(ServiceLifecycle::generation)
                    .findFirst()
                    .orElseGet(() -> mutation.instances().stream()
                            .filter(value -> value.instanceKey().equals(
                                    register.registration().instanceKey()))
                            .mapToLong(value -> value.registration().serviceGeneration())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Registry registration returned no service generation")));
            serviceDefinitions.ensureActiveProjection(
                    key.namespace(),
                    key.group(),
                    key.serviceName(),
                    generation,
                    "client-auto-register");
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "Service registered in Registry State but its management projection could not be updated: service={}, clientInstanceId={}",
                    key.canonicalName(),
                    context.clientInstanceId(),
                    e);
        }
    }

    private RegisterLease register(
            ControlPlaneRequestContext context,
            RegistryActor actor,
            Envelope envelope) throws InvalidProtocolBufferException {
        DiscoveryRegisterRequest request = DiscoveryRegisterRequest.parseFrom(
                envelope.getPayload());
        InstanceKey key = new InstanceKey(
                RegistryControlPlaneSupport.service(
                        context.namespaceId(),
                        context.groupName(),
                        "",
                        request.getGroupName(),
                        request.getServiceName()),
                request.getInstanceId());
        ServiceRegistration registration = new ServiceRegistration(
                key,
                request.getExpectedServiceGeneration(),
                request.getIp(),
                request.getPort(),
                request.getWeight() == 0D ? 1D : request.getWeight(),
                request.getEnabled(),
                request.getMetadata());
        return new RegisterLease(
                actor,
                registration,
                assignedLeaseId(actor, envelope.getOperationId()),
                request.getTtlMs(),
                System.currentTimeMillis());
    }

    private RenewLeaseBatch renew(
            ControlPlaneRequestContext context,
            RegistryActor actor,
            Envelope envelope) throws InvalidProtocolBufferException {
        DiscoveryRenewBatchRequest request = DiscoveryRenewBatchRequest.parseFrom(
                envelope.getPayload());
        List<LeaseRenewal> renewals = request.getRenewalsList().stream()
                .map(value -> new LeaseRenewal(
                        lease(context, value.getLease()),
                        value.getRenewSequence(),
                        value.getTtlMs()))
                .toList();
        return new RenewLeaseBatch(actor, renewals, System.currentTimeMillis());
    }

    private DeregisterLease deregister(
            ControlPlaneRequestContext context,
            RegistryActor actor,
            Envelope envelope) throws InvalidProtocolBufferException {
        DiscoveryDeregisterRequest request = DiscoveryDeregisterRequest.parseFrom(
                envelope.getPayload());
        return new DeregisterLease(
                actor, lease(context, request.getLease()), System.currentTimeMillis());
    }

    private TakeoverLease takeover(
            ControlPlaneRequestContext context,
            RegistryActor actor,
            Envelope envelope) throws InvalidProtocolBufferException {
        DiscoveryTakeoverRequest request = DiscoveryTakeoverRequest.parseFrom(
                envelope.getPayload());
        return new TakeoverLease(
                actor,
                lease(context, request.getExpectedLease()),
                assignedLeaseId(actor, envelope.getOperationId()),
                request.getTtlMs(),
                System.currentTimeMillis());
    }

    private LeaseReference lease(
            ControlPlaneRequestContext context, DiscoveryLeaseReference value) {
        return new LeaseReference(
                RegistryControlPlaneSupport.instance(
                        context.namespaceId(), context.groupName(), value.getInstance()),
                value.getLeaseId(),
                value.getLeaseEpoch(),
                value.getRecoveryEpoch());
    }

    private String assignedLeaseId(RegistryActor actor, String operationId) {
        String source = actor.idempotencyScope() + "\u0000" + operationId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
