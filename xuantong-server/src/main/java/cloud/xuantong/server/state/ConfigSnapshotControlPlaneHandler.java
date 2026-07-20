package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigSnapshot;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.RolloutRule;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestHandler;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ConfigDecisionSummary;
import cloud.xuantong.protocol.v2.ConfigRuleSummary;
import cloud.xuantong.protocol.v2.ConfigSnapshotRequest;
import cloud.xuantong.protocol.v2.ConfigSnapshotResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevision;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class ConfigSnapshotControlPlaneHandler implements ControlPlaneRequestHandler {
    private final ControlPlaneStateExecutor stateExecutor;

    ConfigSnapshotControlPlaneHandler(ControlPlaneStateExecutor stateExecutor) {
        this.stateExecutor = stateExecutor;
    }

    @Override
    public String event() {
        return ControlPlaneProtocol.CONFIG_SNAPSHOT;
    }

    @Override
    public CompletionStage<ControlPlaneReply> handle(
            ControlPlaneRequestContext context, Envelope request) {
        try {
            if (!ControlPlaneProtocol.CONFIG_SNAPSHOT_REQUEST_TYPE.equals(
                    request.getPayloadType())) {
                throw new IllegalArgumentException(
                        "payloadType must be "
                                + ControlPlaneProtocol.CONFIG_SNAPSHOT_REQUEST_TYPE);
            }
            StateGroupId groupId = ConfigControlPlaneSupport.requireConfigGroup(
                    request, RevisionType.CONFIG_EVENT);
            ConfigSnapshotRequest snapshotRequest =
                    ConfigSnapshotRequest.parseFrom(request.getPayload());
            List<ConfigKey> keys = snapshotRequest.getConfigsList().stream()
                    .map(config -> ConfigControlPlaneSupport.key(
                            context.namespaceId(),
                            context.groupName(),
                            config.getNamespaceId(),
                            config.getGroupName(),
                            config.getDataId()))
                    .distinct()
                    .sorted()
                    .toList();
            if (keys.isEmpty()) {
                throw new IllegalArgumentException(
                        "config/snapshot requires at least one config coordinate");
            }
            ReadOptions readOptions = request.getMinRevision() == 0
                    ? ReadOptions.linearizable()
                    : ReadOptions.linearizable(StateRevision.configEvent(
                            groupId, request.getMinRevision()));
            return stateExecutor.query(
                            ConfigStateCodec.snapshotQuery(
                                    groupId,
                                    new cloud.xuantong.config.state.ConfigSnapshotRequest(keys),
                                    readOptions),
                            context)
                    .thenApply(result -> {
                        if (!ConfigStateCodec.RESULT_SNAPSHOT.equals(result.resultType())) {
                            throw new CompletionException(new IllegalStateException(
                                    "Config State returned an unexpected result type"));
                        }
                        try {
                            return ConfigControlPlaneSupport.ok(
                                    ControlPlaneProtocol.CONFIG_SNAPSHOT_RESPONSE_TYPE,
                                    response(ConfigStateCodec.decodeSnapshot(
                                            result.payload())).toByteArray());
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            return ConfigControlPlaneSupport.invalid(e.getMessage());
        }
    }

    private ConfigSnapshotResponse response(ConfigSnapshot snapshot) {
        ConfigSnapshotResponse.Builder response = ConfigSnapshotResponse.newBuilder()
                .setEventRevision(snapshot.eventRevision())
                .setCompactionRevision(snapshot.compactionRevision());
        for (ReleaseDecision decision : snapshot.decisions()) {
            ConfigDecisionSummary.Builder summary = ConfigDecisionSummary.newBuilder()
                    .setConfig(ConfigControlPlaneSupport.coordinate(decision.configKey()))
                    .setDecisionRevision(decision.decisionRevision())
                    .setStableContentRevision(decision.stableContentRevision())
                    .setState(decision.tombstone()
                            ? cloud.xuantong.protocol.v2.ConfigValueState
                                    .CONFIG_VALUE_STATE_TOMBSTONE
                            : cloud.xuantong.protocol.v2.ConfigValueState
                                    .CONFIG_VALUE_STATE_ACTIVE);
            for (RolloutRule rule : decision.rules()) {
                summary.addRules(ConfigRuleSummary.newBuilder()
                        .setRuleId(rule.ruleId())
                        .setRuleGeneration(rule.ruleGeneration())
                        .setTargetContentRevision(rule.targetContentRevision())
                        .setActive(rule.status() == RolloutRuleStatus.ACTIVE));
            }
            response.addDecisions(summary);
        }
        return response.build();
    }
}
