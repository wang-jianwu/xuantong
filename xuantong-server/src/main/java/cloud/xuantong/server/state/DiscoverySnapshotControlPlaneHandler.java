package cloud.xuantong.server.state;

import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoverySnapshotRequest;
import cloud.xuantong.protocol.v2.DiscoverySnapshotResponse;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.registry.state.RegistrySnapshot;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.state.api.StateGroupId;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class DiscoverySnapshotControlPlaneHandler implements ControlPlaneRequestHandler {
    private final ControlPlaneStateExecutor stateExecutor;

    DiscoverySnapshotControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.DISCOVERY_SNAPSHOT;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.DISCOVERY_SNAPSHOT_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException("payloadType must be "
                        + ControlPlaneProtocol.DISCOVERY_SNAPSHOT_REQUEST_TYPE);
            }
            StateGroupId groupId = RegistryControlPlaneSupport.requireRegistryGroup(request);
            DiscoverySnapshotRequest payload = DiscoverySnapshotRequest.parseFrom(
                    request.getPayload());
            if (!context.groupName().equals(payload.getGroupName())) {
                throw new IllegalArgumentException(
                        "Discovery Snapshot is outside the authorized group");
            }
            cloud.xuantong.registry.state.RegistrySnapshotRequest stateRequest =
                    new cloud.xuantong.registry.state.RegistrySnapshotRequest(
                            context.namespaceId(),
                            payload.getGroupName(),
                            payload.getServiceNamesList().stream().distinct().sorted().toList());
            return stateExecutor.query(
                            RegistryStateCodec.snapshotQuery(
                                    groupId,
                                    stateRequest,
                                    RegistryControlPlaneSupport.readOptions(request, groupId)),
                            context)
                    .thenApply(result -> {
                        if (!RegistryStateCodec.RESULT_SNAPSHOT.equals(result.resultType())) {
                            throw new CompletionException(new IllegalStateException(
                                    "Registry State returned an unexpected snapshot type"));
                        }
                        try {
                            RegistrySnapshot snapshot = RegistryStateCodec.decodeSnapshot(
                                    result.payload());
                            DiscoverySnapshotResponse.Builder response =
                                    DiscoverySnapshotResponse.newBuilder()
                                            .setRegistryRevision(snapshot.registryRevision())
                                            .setCompactionRevision(snapshot.compactionRevision())
                                            .setServerTimeEpochMs(snapshot.serverTimeEpochMs());
                            snapshot.instances().forEach(instance -> response.addInstances(
                                    RegistryControlPlaneSupport.instance(instance)));
                            return RegistryControlPlaneSupport.ok(
                                    ControlPlaneProtocol.DISCOVERY_SNAPSHOT_RESPONSE_TYPE,
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
