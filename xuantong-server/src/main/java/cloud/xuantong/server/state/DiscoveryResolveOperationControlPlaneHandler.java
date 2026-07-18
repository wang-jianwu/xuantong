package cloud.xuantong.server.state;

import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoveryResolveOperationRequest;
import cloud.xuantong.protocol.v2.DiscoveryResolveOperationResponse;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryMutationError;
import cloud.xuantong.registry.state.RegistryMutationResult;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.ResolveRegistryOperationRequest;
import cloud.xuantong.registry.state.ResolvedRegistryOperation;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateGroupId;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class DiscoveryResolveOperationControlPlaneHandler
        implements ControlPlaneRequestHandler {
    private final ControlPlaneStateExecutor stateExecutor;

    DiscoveryResolveOperationControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException("payloadType must be "
                        + ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION_REQUEST_TYPE);
            }
            StateGroupId groupId = RegistryControlPlaneSupport.requireRegistryGroup(request);
            DiscoveryResolveOperationRequest payload =
                    DiscoveryResolveOperationRequest.parseFrom(request.getPayload());
            RegistryActor actor = new RegistryActor(
                    context.tenant(),
                    context.clientInstanceId(),
                    context.applicationName());
            return stateExecutor.query(
                            RegistryStateCodec.resolveOperationQuery(
                                    groupId,
                                    new ResolveRegistryOperationRequest(
                                            actor, payload.getOperationId()),
                                    RegistryControlPlaneSupport.readOptions(request, groupId)),
                            context)
                    .thenApply(result -> {
                        if (!RegistryStateCodec.RESULT_RESOLVED_OPERATION.equals(
                                result.resultType())) {
                            throw new CompletionException(new IllegalStateException(
                                    "Registry State returned an unexpected operation type"));
                        }
                        try {
                            ResolvedRegistryOperation resolved =
                                    RegistryStateCodec.decodeResolvedOperation(result.payload());
                            DiscoveryResolveOperationResponse.Builder response =
                                    DiscoveryResolveOperationResponse.newBuilder()
                                            .setFound(resolved.found());
                            if (resolved.found()) {
                                response.setApplied(resolved.status() == ApplyStatus.APPLIED);
                                if (RegistryStateCodec.RESULT_MUTATION.equals(
                                        resolved.resultType())) {
                                    RegistryMutationResult mutation =
                                            RegistryStateCodec.decodeMutationResult(
                                                    resolved.payload());
                                    response.setResult(
                                            RegistryControlPlaneSupport.mutation(mutation));
                                } else if (RegistryStateCodec.RESULT_MUTATION_ERROR.equals(
                                        resolved.resultType())) {
                                    RegistryMutationError error =
                                            RegistryStateCodec.decodeMutationError(
                                                    resolved.payload());
                                    response.setErrorCode(error.code())
                                            .setErrorMessage(error.message());
                                } else {
                                    throw new IOException(
                                            "Resolved Registry operation has unknown type");
                                }
                            }
                            return RegistryControlPlaneSupport.ok(
                                    ControlPlaneProtocol
                                            .DISCOVERY_RESOLVE_OPERATION_RESPONSE_TYPE,
                                    response.build().toByteArray());
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            return RegistryControlPlaneSupport.invalid(e.getMessage());
        }
    }
}
