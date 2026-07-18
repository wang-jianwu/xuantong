package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.model.RolloutStatus;
import cloud.xuantong.config.management.repository.AuditLogRepository;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.management.repository.ConfigStateOperationRepository;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.annotation.Transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rebuilds idempotent SQL projections after the Config Raft commit. */
@Component
public class ConfigStateProjectionService {
    @Inject
    private ConfigResourceRepository resourceRepository;
    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private ConfigRolloutRepository rolloutRepository;
    @Inject
    private AuditLogRepository auditLogRepository;
    @Inject
    private ConfigStateOperationRepository operationRepository;

    @Transaction
    public void project(
            ConfigStateOperation operation,
            ConfigStateProjectionPlan plan,
            ConfigStateCommit commit) {
        ConfigResource resource = resourceRepository.find(ConfigResourceKey.of(
                plan.getNamespaceId(), plan.getGroupName(), plan.getDataId()));
        if (resource == null || !plan.getConfigId().equals(resource.getId())) {
            throw new IllegalStateException(
                    "Config projection resource is missing: "
                            + plan.getNamespaceId() + "/" + plan.getGroupName()
                            + "/" + plan.getDataId());
        }

        String projectionOperationId = projectionOperationId(operation);
        ConfigRelease expectedRelease = plan.toRelease(commit, projectionOperationId);
        ConfigRelease existingRelease = releaseRepository.findByReleaseId(
                expectedRelease.getReleaseId());
        if (existingRelease == null) {
            if (releaseRepository.save(expectedRelease) != 1) {
                throw new IllegalStateException(
                        "Failed to project Config release " + expectedRelease.getReleaseId());
            }
        } else {
            validateRelease(existingRelease, expectedRelease);
        }

        ConfigRollout expectedRollout = plan.toRollout(commit, projectionOperationId);
        if (expectedRollout != null) {
            projectRollout(plan, expectedRollout, commit, projectionOperationId);
        }

        resourceRepository.advanceRevision(resource.getId(), commit.decisionRevision());
        ConfigResource projectedResource = resourceRepository.find(ConfigResourceKey.of(
                plan.getNamespaceId(), plan.getGroupName(), plan.getDataId()));
        if (projectedResource == null
                || projectedResource.getRevision() == null
                || projectedResource.getRevision() < commit.decisionRevision()) {
            throw new IllegalStateException(
                    "Failed to advance Config projection revision to "
                            + commit.decisionRevision());
        }

        if (auditLogRepository.findByOperationId(projectionOperationId) == null) {
            AuditLog audit = audit(operation, plan, commit, projectionOperationId);
            if (auditLogRepository.save(audit) != 1) {
                throw new IllegalStateException(
                        "Failed to project Config audit for " + operation.getOperationId());
            }
        }

        if (operationRepository.markProjected(operation.getId()) != 1) {
            throw new IllegalStateException(
                    "Failed to mark Config operation projected: "
                            + operation.getOperationId());
        }
    }

    private void projectRollout(
            ConfigStateProjectionPlan plan,
            ConfigRollout expected,
            ConfigStateCommit commit,
            String projectionOperationId) {
        ConfigRollout existing = rolloutRepository.findByRolloutId(expected.getRolloutId());
        if ("START".equals(plan.getRolloutAction())) {
            if (existing == null) {
                if (rolloutRepository.save(expected) != 1) {
                    throw new IllegalStateException(
                            "Failed to project Config rollout " + expected.getRolloutId());
                }
            } else {
                validateRolloutIdentity(existing, expected);
            }
            return;
        }

        if (existing == null) {
            throw new IllegalStateException(
                    "Config rollout projection is missing: " + expected.getRolloutId());
        }
        RolloutStatus target = RolloutStatus.valueOf(expected.getStatus());
        if (RolloutStatus.ACTIVE.name().equals(existing.getStatus())) {
            rolloutRepository.completeProjection(
                    existing.getRolloutId(),
                    RolloutStatus.ACTIVE,
                    target,
                    plan.getOperator(),
                    projectionOperationId,
                    commit.decisionRevision());
            existing = rolloutRepository.findByRolloutId(expected.getRolloutId());
        }
        if (existing == null || !target.name().equals(existing.getStatus())) {
            throw new IllegalStateException(
                    "Failed to complete Config rollout " + expected.getRolloutId());
        }
    }

    private static void validateRelease(ConfigRelease actual, ConfigRelease expected) {
        if (!expected.getOperationId().equals(actual.getOperationId())
                || !expected.getConfigId().equals(actual.getConfigId())
                || !expected.getDecisionRevision().equals(actual.getDecisionRevision())
                || !expected.getEventRevision().equals(actual.getEventRevision())
                || !expected.getChecksum().equals(actual.getChecksum())) {
            throw new IllegalStateException(
                    "Config release projection conflicts with the committed operation: "
                            + expected.getReleaseId());
        }
    }

    private static void validateRolloutIdentity(ConfigRollout actual, ConfigRollout expected) {
        if (!expected.getConfigId().equals(actual.getConfigId())
                || !expected.getCandidateReleaseId().equals(actual.getCandidateReleaseId())
                || !expected.getBaselineReleaseId().equals(actual.getBaselineReleaseId())
                || !expected.getStartOperationId().equals(actual.getStartOperationId())) {
            throw new IllegalStateException(
                    "Config rollout projection conflicts with the committed operation: "
                            + expected.getRolloutId());
        }
    }

    private static AuditLog audit(
            ConfigStateOperation operation,
            ConfigStateProjectionPlan plan,
            ConfigStateCommit commit,
            String projectionOperationId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operationId", operation.getOperationId());
        detail.put("releaseId", plan.getReleaseId());
        detail.put("releaseType", plan.getReleaseType());
        detail.put("contentRevision", plan.getReferencedContentRevision() != null
                && plan.getReferencedContentRevision() > 0
                ? plan.getReferencedContentRevision()
                : commit.contentRevision());
        detail.put("decisionRevision", commit.decisionRevision());
        detail.put("eventRevision", commit.eventRevision());
        detail.put("checksum", plan.getChecksum());
        detail.put("batch", Boolean.TRUE.equals(plan.getBatch()));
        if (plan.getRolloutId() != null) detail.put("rolloutId", plan.getRolloutId());
        if (plan.getTargetReleaseId() != null) {
            detail.put("targetReleaseId", plan.getTargetReleaseId());
        }

        AuditLog audit = new AuditLog();
        audit.setNamespaceId(plan.getNamespaceId());
        audit.setGroupName(plan.getGroupName());
        audit.setResourceType("CONFIG");
        audit.setResourceName(plan.getDataId());
        audit.setOperation(plan.getAuditOperation());
        audit.setOperator(plan.getOperator());
        audit.setDetail(ONode.serialize(detail));
        audit.setOperationId(projectionOperationId);
        audit.setCreatedAt(new Date(plan.getCreatedAtEpochMs()));
        return audit;
    }

    static String projectionOperationId(ConfigStateOperation operation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getTenant().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(operation.getPrincipal().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(operation.getOperationId().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
