package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ConfigChangeEvent;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigWatchSelector;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ConfigInvalidation;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchEvent;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class ConfigWatchBatchControlPlaneHandler implements ControlPlaneRequestHandler {
    private static final int DEFAULT_BATCH_SIZE = 256;
    private static final int MAX_BATCH_SIZE = 1000;

    private final ControlPlaneStateExecutor stateExecutor;

    ConfigWatchBatchControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException(
                        "payloadType must be "
                                + ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE);
            }
            StateGroupId groupId = ConfigControlPlaneSupport.requireConfigGroup(
                    request, RevisionType.CONFIG_EVENT);
            ConfigWatchBatchRequest watchRequest =
                    ConfigWatchBatchRequest.parseFrom(request.getPayload());
            String groupName = ConfigControlPlaneSupport.required(
                    "groupName", watchRequest.getGroupName());
            if (!context.groupName().equals(groupName)) {
                throw new IllegalArgumentException(
                        "Config Watch is outside the authorized group");
            }
            if (request.getKnownRevision() != 0
                    && request.getKnownRevision()
                    != watchRequest.getAfterEventRevision()) {
                throw new IllegalArgumentException(
                        "knownRevision must match afterEventRevision");
            }
            List<ConfigKey> keys = watchRequest.getConfigsList().stream()
                    .map(config -> ConfigControlPlaneSupport.key(
                            context.namespaceId(),
                            context.groupName(),
                            config.getNamespaceId(),
                            config.getGroupName(),
                            config.getDataId()))
                    .distinct()
                    .sorted()
                    .toList();
            if (keys.stream().anyMatch(key -> !groupName.equals(key.group()))) {
                throw new IllegalArgumentException(
                        "Config Watch coordinates must match groupName");
            }
            int maxBatchSize = watchRequest.getMaxBatchSize() == 0
                    ? DEFAULT_BATCH_SIZE : watchRequest.getMaxBatchSize();
            if (maxBatchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        "maxBatchSize must not exceed " + MAX_BATCH_SIZE);
            }
            ReadOptions readOptions = request.getMinRevision() == 0
                    ? ReadOptions.linearizable()
                    : ReadOptions.linearizable(StateRevision.configEvent(
                            groupId, request.getMinRevision()));
            return stateExecutor.watch(
                            ConfigStateCodec.changesWatch(
                                    groupId,
                                    watchRequest.getAfterEventRevision(),
                                    new ConfigWatchSelector(
                                            context.namespaceId(), groupName, keys),
                                    maxBatchSize,
                                    readOptions),
                            context)
                    .thenApply(batch -> {
                        try {
                            ConfigWatchBatchResponse.Builder response =
                                    ConfigWatchBatchResponse.newBuilder()
                                            .setRequestedAfterRevision(
                                                    batch.requestedAfter().value())
                                            .setCoveredThroughRevision(
                                                    batch.coveredThrough().value())
                                            .setCompactionRevision(
                                                    batch.compactionRevision().value())
                                            .setResetRequired(batch.resetRequired());
                            for (WatchEvent event : batch.events()) {
                                if (!ConfigStateCodec.EVENT_INVALIDATED.equals(
                                        event.eventType())) {
                                    throw new IOException(
                                            "Unexpected Config Watch event type");
                                }
                                ConfigChangeEvent change =
                                        ConfigStateCodec.decodeChangeEvent(event.payload());
                                response.addEvents(ConfigInvalidation.newBuilder()
                                        .setEventRevision(change.eventRevision())
                                        .setConfig(ConfigControlPlaneSupport.coordinate(
                                                change.configKey()))
                                        .setDecisionRevision(change.decisionRevision()));
                            }
                            return ConfigControlPlaneSupport.ok(
                                    ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                                    response.build().toByteArray());
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            return ConfigControlPlaneSupport.invalid(e.getMessage());
        }
    }
}
