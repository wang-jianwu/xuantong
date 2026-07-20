package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigRollout;
import lombok.Data;

import java.util.Date;

/** Immutable-in-storage instructions for rebuilding SQL query projections. */
@Data
public class ConfigStateProjectionPlan {
    private Long configId;
    private String namespaceId;
    private String groupName;
    private String dataId;
    private String operator;
    private String releaseId;
    private String releaseType;
    private String lifecycleStatus;
    private String content;
    private String contentType;
    private String checksum;
    private String auditOperation;
    private Boolean batch;
    private Long referencedContentRevision;
    private String rolloutAction;
    private String rolloutId;
    private String baselineReleaseId;
    private String candidateReleaseId;
    private String rolloutType;
    private String targetValue;
    private String rolloutKey;
    private String rolloutStatus;
    private String rolloutCreatedBy;
    private Long rolloutCreatedAtEpochMs;
    private Long createdAtEpochMs;
    private String targetReleaseId;

    ConfigRelease toRelease(ConfigStateCommit commit, String operationId) {
        ConfigRelease release = new ConfigRelease();
        release.setReleaseId(releaseId);
        release.setConfigId(configId);
        release.setNamespaceId(namespaceId);
        release.setGroupName(groupName);
        release.setDataId(dataId);
        release.setRevision(commit.decisionRevision());
        long effectiveContentRevision = commit.contentRevision() > 0
                ? commit.contentRevision()
                : referencedContentRevision == null ? 0L : referencedContentRevision;
        release.setContentRevision(effectiveContentRevision > 0
                ? effectiveContentRevision
                : null);
        release.setDecisionRevision(commit.decisionRevision());
        release.setEventRevision(commit.eventRevision());
        release.setContent(content);
        release.setContentType(contentType);
        release.setChecksum(checksum);
        release.setReleaseType(releaseType);
        release.setOperator(operator);
        release.setOperationId(operationId);
        release.setReleasedAt(new Date(createdAtEpochMs));
        return release;
    }

    ConfigRollout toRollout(ConfigStateCommit commit, String operationId) {
        if (rolloutId == null || rolloutId.isBlank()) {
            return null;
        }
        ConfigRollout rollout = new ConfigRollout();
        rollout.setRolloutId(rolloutId);
        rollout.setConfigId(configId);
        rollout.setNamespaceId(namespaceId);
        rollout.setGroupName(groupName);
        rollout.setDataId(dataId);
        rollout.setBaselineReleaseId(baselineReleaseId);
        rollout.setCandidateReleaseId(candidateReleaseId);
        rollout.setRolloutType(rolloutType);
        rollout.setTargetValue(targetValue);
        rollout.setRolloutKey(rolloutKey);
        rollout.setStatus(rolloutStatus);
        rollout.setCreatedBy(rolloutCreatedBy);
        rollout.setCreatedAt(new Date(rolloutCreatedAtEpochMs));
        rollout.setDecisionRevision(commit.decisionRevision());
        rollout.setStartOperationId("START".equals(rolloutAction)
                ? operationId
                : null);
        if (!"START".equals(rolloutAction)) {
            rollout.setCompletedBy(operator);
            rollout.setCompletedAt(new Date(createdAtEpochMs));
            rollout.setCompleteOperationId(operationId);
        }
        return rollout;
    }
}
