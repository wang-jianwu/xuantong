package cloud.xuantong.server.state;

import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoveryGetLeaseStateRequest;
import cloud.xuantong.protocol.v2.DiscoveryGetLeaseStateResponse;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.registry.state.GetLeaseStateRequest;
import cloud.xuantong.registry.state.LeaseState;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.state.api.StateGroupId;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class DiscoveryLeaseStateControlPlaneHandler implements ControlPlaneRequestHandler {
    private final ControlPlaneStateExecutor stateExecutor;

    DiscoveryLeaseStateControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.DISCOVERY_GET_LEASE_STATE;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.DISCOVERY_GET_LEASE_STATE_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException("payloadType must be "
                        + ControlPlaneProtocol.DISCOVERY_GET_LEASE_STATE_REQUEST_TYPE);
            }
            StateGroupId groupId = RegistryControlPlaneSupport.requireRegistryGroup(request);
            DiscoveryGetLeaseStateRequest payload =
                    DiscoveryGetLeaseStateRequest.parseFrom(request.getPayload());
            return stateExecutor.query(
                            RegistryStateCodec.leaseStateQuery(
                                    groupId,
                                    new GetLeaseStateRequest(
                                            RegistryControlPlaneSupport.instance(
                                                    context.namespaceId(),
                                                    context.groupName(),
                                                    payload.getInstance())),
                                    RegistryControlPlaneSupport.readOptions(request, groupId)),
                            context)
                    .thenApply(result -> {
                        if (!RegistryStateCodec.RESULT_LEASE_STATE.equals(
                                result.resultType())) {
                            throw new CompletionException(new IllegalStateException(
                                    "Registry State returned an unexpected lease-state type"));
                        }
                        try {
                            LeaseState state = RegistryStateCodec.decodeLeaseState(
                                    result.payload());
                            DiscoveryGetLeaseStateResponse.Builder response =
                                    DiscoveryGetLeaseStateResponse.newBuilder()
                                            .setFound(state.found())
                                            .setRegistryRevision(state.registryRevision())
                                            .setServerTimeEpochMs(state.serverTimeEpochMs());
                            if (state.found()) {
                                response.setInstance(RegistryControlPlaneSupport.instance(
                                        state.instance()));
                            }
                            return RegistryControlPlaneSupport.ok(
                                    ControlPlaneProtocol
                                            .DISCOVERY_GET_LEASE_STATE_RESPONSE_TYPE,
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
