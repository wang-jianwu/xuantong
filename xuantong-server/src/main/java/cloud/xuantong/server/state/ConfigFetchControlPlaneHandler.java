package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ApplicableRelease;
import cloud.xuantong.config.state.ApplicableReleaseRequest;
import cloud.xuantong.config.state.ConfigClientIdentity;
import cloud.xuantong.config.state.ConfigContent;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ConfigContentValue;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.StateGroupId;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class ConfigFetchControlPlaneHandler implements ControlPlaneRequestHandler {
    private final ControlPlaneStateExecutor stateExecutor;

    ConfigFetchControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.CONFIG_FETCH;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException(
                        "payloadType must be "
                                + ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE);
            }
            StateGroupId groupId = ConfigControlPlaneSupport.requireConfigGroup(
                    request, RevisionType.CONFIG_DECISION);
            ConfigFetchRequest fetch = ConfigFetchRequest.parseFrom(request.getPayload());
            ConfigKey key = ConfigControlPlaneSupport.key(
                    context.namespaceId(), context.groupName(), "",
                    fetch.getGroupName(), fetch.getDataId());
            ApplicableReleaseRequest stateRequest = new ApplicableReleaseRequest(
                    key,
                    new ConfigClientIdentity(
                            context.clientInstanceId(),
                            context.applicationName(),
                            context.remoteIp(),
                            Map.of()));
            return stateExecutor.query(
                            ConfigStateCodec.applicableReleaseQuery(
                                    groupId,
                                    stateRequest,
                                    ConfigControlPlaneSupport.readOptions(
                                            request, groupId, key)),
                            context)
                    .thenApply(result -> {
                        if (!ConfigStateCodec.RESULT_APPLICABLE_RELEASE.equals(
                                result.resultType())) {
                            throw new CompletionException(new IllegalStateException(
                                    "Config State returned an unexpected result type"));
                        }
                        try {
                            return ConfigControlPlaneSupport.ok(
                                    ControlPlaneProtocol.CONFIG_FETCH_RESPONSE_TYPE,
                                    response(ConfigStateCodec.decodeApplicableRelease(
                                            result.payload())).toByteArray());
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            return ConfigControlPlaneSupport.invalid(e.getMessage());
        }
    }

    private ConfigFetchResponse response(ApplicableRelease release) {
        if (!release.found()) {
            return ConfigFetchResponse.newBuilder().setFound(false).build();
        }
        ConfigContent content = release.content();
        return ConfigFetchResponse.newBuilder()
                .setFound(true)
                .setConfig(ConfigControlPlaneSupport.coordinate(release.configKey()))
                .setDecisionRevision(release.decisionRevision())
                .setContent(ConfigContentValue.newBuilder()
                        .setContentRevision(content.contentRevision())
                        .setContentHash(content.contentHash())
                        .setContentType(content.contentType())
                        .setSchemaVersion(content.schemaVersion())
                        .setPayload(com.google.protobuf.ByteString.copyFrom(content.payload()))
                        .setBlobReference(content.blobReference()))
                .setMatchedRuleId(release.matchedRuleId())
                .build();
    }
}
