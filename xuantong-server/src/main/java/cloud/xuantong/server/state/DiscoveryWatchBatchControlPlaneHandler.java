package cloud.xuantong.server.state;

import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoveryChange;
import cloud.xuantong.protocol.v2.DiscoveryWatchBatchRequest;
import cloud.xuantong.protocol.v2.DiscoveryWatchBatchResponse;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.registry.state.RegistryChangeEvent;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.WatchEvent;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class DiscoveryWatchBatchControlPlaneHandler implements ControlPlaneRequestHandler {
    private static final int DEFAULT_BATCH_SIZE = 256;
    private static final int MAX_BATCH_SIZE = 1_000;

    private final ControlPlaneStateExecutor stateExecutor;

    DiscoveryWatchBatchControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.DISCOVERY_WATCH_BATCH;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException("payloadType must be "
                        + ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_REQUEST_TYPE);
            }
            StateGroupId groupId = RegistryControlPlaneSupport.requireRegistryGroup(request);
            DiscoveryWatchBatchRequest payload = DiscoveryWatchBatchRequest.parseFrom(
                    request.getPayload());
            if (!context.groupName().equals(payload.getGroupName())) {
                throw new IllegalArgumentException(
                        "Discovery Watch is outside the authorized group");
            }
            if (request.getKnownRevision() != 0
                    && request.getKnownRevision() != payload.getAfterRegistryRevision()) {
                throw new IllegalArgumentException(
                        "knownRevision must match afterRegistryRevision");
            }
            int maxBatchSize = payload.getMaxBatchSize() == 0
                    ? DEFAULT_BATCH_SIZE : payload.getMaxBatchSize();
            if (maxBatchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        "maxBatchSize must not exceed " + MAX_BATCH_SIZE);
            }
            cloud.xuantong.registry.state.RegistrySnapshotRequest selector =
                    new cloud.xuantong.registry.state.RegistrySnapshotRequest(
                            context.namespaceId(),
                            payload.getGroupName(),
                            payload.getServiceNamesList().stream().distinct().sorted().toList());
            return stateExecutor.watch(
                            RegistryStateCodec.changesWatch(
                                    groupId,
                                    payload.getAfterRegistryRevision(),
                                    selector,
                                    maxBatchSize,
                                    RegistryControlPlaneSupport.readOptions(request, groupId)),
                            context)
                    .thenApply(batch -> {
                        try {
                            DiscoveryWatchBatchResponse.Builder response =
                                    DiscoveryWatchBatchResponse.newBuilder()
                                            .setRequestedAfterRevision(
                                                    batch.requestedAfter().value())
                                            .setCoveredThroughRevision(
                                                    batch.coveredThrough().value())
                                            .setCompactionRevision(
                                                    batch.compactionRevision().value())
                                            .setResetRequired(batch.resetRequired());
                            for (WatchEvent event : batch.events()) {
                                if (!RegistryStateCodec.EVENT_INSTANCE_CHANGED.equals(
                                        event.eventType())) {
                                    throw new IOException(
                                            "Unexpected Registry Watch event type");
                                }
                                RegistryChangeEvent change =
                                        RegistryStateCodec.decodeChangeEvent(event.payload());
                                DiscoveryChange.Builder wire = DiscoveryChange.newBuilder()
                                        .setRegistryRevision(change.registryRevision())
                                        .setEventType(change.eventType())
                                        .setInstance(RegistryControlPlaneSupport.coordinate(
                                                change.instanceKey()));
                                if (change.instance() != null) {
                                    wire.setValue(RegistryControlPlaneSupport.instance(
                                            change.instance()));
                                }
                                response.addEvents(wire);
                            }
                            return RegistryControlPlaneSupport.ok(
                                    ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_RESPONSE_TYPE,
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
