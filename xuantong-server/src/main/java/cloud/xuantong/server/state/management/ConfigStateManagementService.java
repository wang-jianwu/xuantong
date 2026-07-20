package cloud.xuantong.server.state.management;

import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigDecisionState;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigMutationError;
import cloud.xuantong.config.state.ConfigMutationResult;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.ResolvedConfigOperation;
import cloud.xuantong.config.state.RolloutRule;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigLifecycleStatus;
import cloud.xuantong.config.management.content.ConfigContentService;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.model.ConfigStateOperationStatus;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.config.management.model.RolloutStatus;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.management.repository.ConfigStateOperationRepository;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateCommand;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Administration write path for Config 2.0.
 *
 * <p>It persists the immutable command first, submits exactly one Config Raft
 * command, and only then rebuilds SQL release/rollout/audit projections. A
 * retry with the same operationId resumes the original command and can never
 * publish different content.</p>
 */
@Slf4j
@Component
public class ConfigStateManagementService {
    private final AtomicLong publishCommittedTotal = new AtomicLong();
    private final AtomicLong rolloutStartedCommittedTotal = new AtomicLong();
    private final AtomicLong rolloutPromotedCommittedTotal = new AtomicLong();
    private final AtomicLong rolloutAbortedCommittedTotal = new AtomicLong();
    private final AtomicLong rollbackCommittedTotal = new AtomicLong();
    private final AtomicLong tombstoneCommittedTotal = new AtomicLong();
    private final AtomicLong commitUnknownTotal = new AtomicLong();
    private final AtomicLong projectionFailureTotal = new AtomicLong();
    private final AtomicLong projectionRecoveredTotal = new AtomicLong();
    @Inject
    private ConfigResourceRepository resourceRepository;
    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private ConfigRolloutRepository rolloutRepository;
    @Inject
    private ConfigStateOperationRepository operationRepository;
    @Inject
    private ConfigStateAccess stateAccess;
    @Inject
    private ConfigContentService contentService;
    @Inject
    private ConfigStateProjectionService projectionService;

    public ConfigStateWriteResult publish(
            String namespaceId,
            String groupName,
            String dataId,
            String operator,
            String operationId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateWriteResult resumed = resumeExisting(
                key, operator, operationId, ConfigStateOperationType.PUBLISH,
                null, null, null);
        if (resumed != null) return resumed;

        requireStateAvailable();
        ConfigResource resource = requireResource(key);
        ensureNoActiveDatabaseRollout(resource);
        ReleaseDecision current = stateAccess.currentDecision(stateKey(key));
        ensureNoActiveStateRollout(current);

        ConfigMutation mutation = new ConfigMutation(
                actor(key, operator),
                stateKey(key),
                decisionRevision(current),
                inlineContent(resource),
                ConfigContentReference.newContent(),
                List.of());
        ConfigStateProjectionPlan plan = releasePlan(
                resource, key, operator, ReleaseType.FULL,
                "CONFIG_PUBLISHED", 0, null);
        return createAndExecute(
                ConfigStateOperationType.PUBLISH, key, operator, operationId, mutation, plan);
    }

    public ConfigStateWriteResult tombstone(
            String namespaceId,
            String groupName,
            String dataId,
            String operator,
            String operationId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateWriteResult resumed = resumeExisting(
                key, operator, operationId, ConfigStateOperationType.TOMBSTONE,
                null, null, null);
        if (resumed != null) return resumed;

        requireStateAvailable();
        ConfigResource resource = requireResource(key);
        ensureNoActiveDatabaseRollout(resource);
        ReleaseDecision current = requirePublishedDecision(key);
        ensureNoActiveStateRollout(current);
        if (current.tombstone()) {
            throw new IllegalStateException(
                    "Config is already tombstoned: " + key.canonicalName());
        }

        ConfigMutation mutation = new ConfigMutation(
                actor(key, operator),
                stateKey(key),
                current.decisionRevision(),
                null,
                ConfigDecisionState.TOMBSTONE,
                null,
                List.of());
        ConfigStateProjectionPlan plan = releasePlan(
                resource, key, operator, ReleaseType.TOMBSTONE,
                "CONFIG_TOMBSTONED", 0, null);
        plan.setLifecycleStatus(ConfigLifecycleStatus.TOMBSTONE.name());
        plan.setContent(null);
        plan.setContentType(null);
        plan.setChecksum(null);
        return createAndExecute(
                ConfigStateOperationType.TOMBSTONE,
                key,
                operator,
                operationId,
                mutation,
                plan);
    }

    public ConfigStateWriteResult startRollout(
            String namespaceId,
            String groupName,
            String dataId,
            ConfigRolloutPolicy policy,
            String rolloutKey,
            String operator,
            String operationId) {
        if (policy == null) {
            throw new IllegalArgumentException("Rollout policy must not be null");
        }
        String normalizedRolloutKey = ConfigRolloutRuleFactory.requireRolloutKey(rolloutKey);
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateWriteResult resumed = resumeExisting(
                key,
                operator,
                operationId,
                ConfigStateOperationType.ROLLOUT_START,
                policy.type().name(),
                policy.targetValue(),
                normalizedRolloutKey);
        if (resumed != null) return resumed;

        requireStateAvailable();
        ConfigResource resource = requireResource(key);
        ensureNoActiveDatabaseRollout(resource);
        ReleaseDecision current = requirePublishedDecision(key);
        requireActiveDecision(key, current);
        ensureNoActiveStateRollout(current);
        ConfigRelease baseline = requireStableProjection(resource, current);

        String rolloutId = UUID.randomUUID().toString();
        cloud.xuantong.config.state.RolloutRuleDraft rule =
                ConfigRolloutRuleFactory.activeCandidate(
                        rolloutId, normalizedRolloutKey, policy);
        ConfigMutation mutation = new ConfigMutation(
                actor(key, operator),
                stateKey(key),
                current.decisionRevision(),
                inlineContent(resource),
                ConfigContentReference.existing(current.stableContentRevision()),
                List.of(rule));
        ConfigStateProjectionPlan plan = releasePlan(
                resource, key, operator, policy.type(),
                "CONFIG_ROLLOUT_STARTED", 0, null);
        plan.setRolloutAction("START");
        plan.setRolloutId(rolloutId);
        plan.setBaselineReleaseId(baseline.getReleaseId());
        plan.setCandidateReleaseId(plan.getReleaseId());
        plan.setRolloutType(policy.type().name());
        plan.setTargetValue(policy.targetValue());
        plan.setRolloutKey(normalizedRolloutKey);
        plan.setRolloutStatus(RolloutStatus.ACTIVE.name());
        plan.setRolloutCreatedBy(operator);
        plan.setRolloutCreatedAtEpochMs(plan.getCreatedAtEpochMs());
        return createAndExecute(
                ConfigStateOperationType.ROLLOUT_START,
                key,
                operator,
                operationId,
                mutation,
                plan);
    }

    public ConfigStateWriteResult promoteRollout(
            String namespaceId,
            String groupName,
            String dataId,
            String rolloutId,
            String operator,
            String operationId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateWriteResult resumed = resumeExisting(
                key,
                operator,
                operationId,
                ConfigStateOperationType.ROLLOUT_PROMOTE,
                rolloutId,
                null,
                null);
        if (resumed != null) return resumed;

        requireStateAvailable();
        ConfigResource resource = requireResource(key);
        ConfigRollout rollout = requireActiveRollout(resource, rolloutId);
        ReleaseDecision current = requirePublishedDecision(key);
        RolloutRule rule = requireActiveRule(current, rolloutId);
        ConfigRelease candidate = requireRelease(
                resource, rollout.getCandidateReleaseId(), "candidate");
        requireContentRevision(candidate, rule.targetContentRevision(), "candidate");

        ConfigMutation mutation = new ConfigMutation(
                actor(key, operator),
                stateKey(key),
                current.decisionRevision(),
                null,
                ConfigContentReference.existing(rule.targetContentRevision()),
                List.of());
        ConfigStateProjectionPlan plan = releasePlan(
                resource, key, operator, ReleaseType.FULL,
                "CONFIG_ROLLOUT_PROMOTED", rule.targetContentRevision(), candidate);
        completeRolloutPlan(plan, rollout, RolloutStatus.PROMOTED);
        return createAndExecute(
                ConfigStateOperationType.ROLLOUT_PROMOTE,
                key,
                operator,
                operationId,
                mutation,
                plan);
    }

    public ConfigStateWriteResult abortRollout(
            String namespaceId,
            String groupName,
            String dataId,
            String rolloutId,
            String operator,
            String operationId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateWriteResult resumed = resumeExisting(
                key,
                operator,
                operationId,
                ConfigStateOperationType.ROLLOUT_ABORT,
                rolloutId,
                null,
                null);
        if (resumed != null) return resumed;

        requireStateAvailable();
        ConfigResource resource = requireResource(key);
        ConfigRollout rollout = requireActiveRollout(resource, rolloutId);
        ReleaseDecision current = requirePublishedDecision(key);
        requireActiveRule(current, rolloutId);
        ConfigRelease baseline = requireRelease(
                resource, rollout.getBaselineReleaseId(), "baseline");
        requireContentRevision(baseline, current.stableContentRevision(), "baseline");

        ConfigMutation mutation = new ConfigMutation(
                actor(key, operator),
                stateKey(key),
                current.decisionRevision(),
                null,
                ConfigContentReference.existing(current.stableContentRevision()),
                List.of());
        ConfigStateProjectionPlan plan = releasePlan(
                resource, key, operator, ReleaseType.ROLLBACK,
                "CONFIG_ROLLOUT_ABORTED", current.stableContentRevision(), baseline);
        completeRolloutPlan(plan, rollout, RolloutStatus.ABORTED);
        return createAndExecute(
                ConfigStateOperationType.ROLLOUT_ABORT,
                key,
                operator,
                operationId,
                mutation,
                plan);
    }

    public ConfigStateWriteResult rollback(
            String namespaceId,
            String groupName,
            String dataId,
            String targetReleaseId,
            String operator,
            String operationId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateWriteResult resumed = resumeExisting(
                key,
                operator,
                operationId,
                ConfigStateOperationType.ROLLBACK,
                targetReleaseId,
                null,
                null);
        if (resumed != null) return resumed;

        requireStateAvailable();
        ConfigResource resource = requireResource(key);
        ensureNoActiveDatabaseRollout(resource);
        ReleaseDecision current = requirePublishedDecision(key);
        ensureNoActiveStateRollout(current);
        ConfigRelease target = requireRelease(resource, targetReleaseId, "rollback target");
        long contentRevision = requireContentRevision(target, null, "rollback target");

        ConfigMutation mutation = new ConfigMutation(
                actor(key, operator),
                stateKey(key),
                current.decisionRevision(),
                null,
                ConfigContentReference.existing(contentRevision),
                List.of());
        ConfigStateProjectionPlan plan = releasePlan(
                resource, key, operator, ReleaseType.ROLLBACK,
                "CONFIG_ROLLED_BACK", contentRevision, target);
        plan.setTargetReleaseId(targetReleaseId);
        return createAndExecute(
                ConfigStateOperationType.ROLLBACK,
                key,
                operator,
                operationId,
                mutation,
                plan);
    }

    public void assertDraftMutable(String namespaceId, String groupName, String dataId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ensureNoUnfinishedOperation(key);
        if (stateAccess.available()) {
            ensureNoActiveStateRollout(stateAccess.currentDecision(stateKey(key)));
        }
    }

    public void assertDraftDeletable(String namespaceId, String groupName, String dataId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigStateOperation latest = operationRepository.findAnyNonFailedForConfig(
                key.namespaceId(), key.groupName(), key.dataId());
        if (latest != null) {
            throw new IllegalStateException(
                    "A Config that entered the authoritative release history cannot be deleted");
        }
        if (stateAccess.available()
                && stateAccess.currentDecision(stateKey(key)) != null) {
            throw new IllegalStateException(
                    "A published Config cannot be deleted from the management projection");
        }
    }

    void recover(ConfigStateOperation operation) {
        executeStored(operation, false);
    }

    public long publishCommittedTotal() { return publishCommittedTotal.get(); }
    public long rolloutStartedCommittedTotal() { return rolloutStartedCommittedTotal.get(); }
    public long rolloutPromotedCommittedTotal() { return rolloutPromotedCommittedTotal.get(); }
    public long rolloutAbortedCommittedTotal() { return rolloutAbortedCommittedTotal.get(); }
    public long rollbackCommittedTotal() { return rollbackCommittedTotal.get(); }
    public long tombstoneCommittedTotal() { return tombstoneCommittedTotal.get(); }
    public long commitUnknownTotal() { return commitUnknownTotal.get(); }
    public long projectionFailureTotal() { return projectionFailureTotal.get(); }
    public long projectionRecoveredTotal() { return projectionRecoveredTotal.get(); }

    private ConfigStateWriteResult createAndExecute(
            ConfigStateOperationType operationType,
            ConfigResourceKey key,
            String operator,
            String operationId,
            ConfigMutation mutation,
            ConfigStateProjectionPlan plan) {
        String normalizedOperationId = requireOperationId(operationId);
        StateCommand command = ConfigStateCodec.mutationCommand(
                stateAccess.groupId(), normalizedOperationId, mutation);
        ConfigStateOperation operation = new ConfigStateOperation();
        operation.setOperationId(normalizedOperationId);
        operation.setTenant(key.namespaceId());
        operation.setPrincipal(requirePrincipal(operator));
        operation.setNamespaceId(key.namespaceId());
        operation.setGroupName(key.groupName());
        operation.setDataId(key.dataId());
        operation.setOperationType(operationType.name());
        operation.setRequestHash(ConfigStateCodec.requestHash(command));
        operation.setCommandType(command.commandType());
        operation.setSchemaVersion(command.schemaVersion());
        operation.setCommandPayload(Base64.getEncoder().encodeToString(command.payload()));
        operation.setProjectionPayload(ONode.serialize(plan));
        operation.setStatus(ConfigStateOperationStatus.PENDING.name());
        operation.setCreatedAt(new Date());
        operation.setUpdatedAt(new Date());
        try {
            if (operationRepository.save(operation) != 1) {
                throw new IllegalStateException("Failed to persist Config operation");
            }
        } catch (RuntimeException saveFailure) {
            ConfigStateOperation existing = operationRepository.find(
                    operation.getTenant(), operation.getPrincipal(), normalizedOperationId);
            if (existing == null) throw saveFailure;
            validateStoredRequest(existing, operationType, key, plan);
            operation = existing;
        }
        return executeStored(operation, true);
    }

    private ConfigStateWriteResult resumeExisting(
            ConfigResourceKey key,
            String operator,
            String operationId,
            ConfigStateOperationType operationType,
            String expectedPrimary,
            String expectedSecondary,
            String expectedRolloutKey) {
        String normalizedOperationId = requireOperationId(operationId);
        ConfigStateOperation existing = operationRepository.find(
                key.namespaceId(), requirePrincipal(operator), normalizedOperationId);
        if (existing == null) return null;
        ConfigStateProjectionPlan plan = decodePlan(existing);
        validateStoredRequest(existing, operationType, key, plan);
        if (operationType == ConfigStateOperationType.ROLLOUT_START) {
            if (!expectedPrimary.equals(plan.getRolloutType())
                    || !expectedSecondary.equals(plan.getTargetValue())
                    || !expectedRolloutKey.equals(plan.getRolloutKey())) {
                throw operationConflict(normalizedOperationId);
            }
        } else if (operationType == ConfigStateOperationType.ROLLOUT_PROMOTE
                || operationType == ConfigStateOperationType.ROLLOUT_ABORT) {
            if (!expectedPrimary.equals(plan.getRolloutId())) {
                throw operationConflict(normalizedOperationId);
            }
        } else if (operationType == ConfigStateOperationType.ROLLBACK
                && !expectedPrimary.equals(plan.getTargetReleaseId())) {
            throw operationConflict(normalizedOperationId);
        }
        return executeStored(existing, false);
    }

    private ConfigStateWriteResult executeStored(
            ConfigStateOperation operation, boolean submitFirst) {
        ConfigStateProjectionPlan plan = decodePlan(operation);
        ConfigStateOperationStatus status = status(operation);
        if (status == ConfigStateOperationStatus.FAILED) {
            throw new ConfigStateWriteException(operation.getErrorMessage(), false);
        }

        ConfigStateCommit commit;
        if (status == ConfigStateOperationStatus.PENDING) {
            requireStateAvailable();
            commit = commitPending(operation, submitFirst);
            operation.setContentRevision(commit.contentRevision());
            operation.setDecisionRevision(commit.decisionRevision());
            operation.setEventRevision(commit.eventRevision());
            operation.setStatus(ConfigStateOperationStatus.COMMITTED.name());
        } else {
            commit = committedRevisions(operation);
        }

        boolean projectionPending = status != ConfigStateOperationStatus.PROJECTED;
        if (projectionPending) {
            try {
                projectionService.project(operation, plan, commit);
                operation.setStatus(ConfigStateOperationStatus.PROJECTED.name());
                projectionPending = false;
                if (status == ConfigStateOperationStatus.PROJECTION_PENDING) {
                    projectionRecoveredTotal.incrementAndGet();
                }
            } catch (RuntimeException projectionFailure) {
                operationRepository.markProjectionPending(
                        operation.getId(), safeMessage(projectionFailure));
                projectionFailureTotal.incrementAndGet();
                log.warn("Config Raft operation committed but SQL projection is pending: operationId={}, decisionRevision={}",
                        operation.getOperationId(), commit.decisionRevision(), projectionFailure);
            }
        }
        String projectionOperationId = ConfigStateProjectionService
                .projectionOperationId(operation);
        ConfigRelease release = plan.toRelease(commit, projectionOperationId);
        ConfigRollout rollout = plan.toRollout(commit, projectionOperationId);
        if (!projectionPending) {
            ConfigRelease projectedRelease = releaseRepository.findByReleaseId(
                    plan.getReleaseId());
            if (projectedRelease != null) {
                release = projectedRelease;
            }
            if (plan.getRolloutId() != null) {
                ConfigRollout projectedRollout = rolloutRepository.findByRolloutId(
                        plan.getRolloutId());
                if (projectedRollout != null) {
                    rollout = projectedRollout;
                }
            }
        }
        return new ConfigStateWriteResult(release, rollout, projectionPending);
    }

    private ConfigStateCommit commitPending(
            ConfigStateOperation operation, boolean submitFirst) {
        StateCommand command = storedCommand(operation);
        try {
            if (!submitFirst) {
                ResolvedConfigOperation resolved = stateAccess.resolve(
                        actor(operation), operation.getOperationId());
                if (resolved.found()) {
                    return acceptResolved(operation, resolved);
                }
            }
            ApplyResult applied = stateAccess.submit(command);
            return acceptApplied(operation, applied);
        } catch (ConfigStateWriteException definitive) {
            throw definitive;
        } catch (RuntimeException submitFailure) {
            try {
                ResolvedConfigOperation resolved = stateAccess.resolve(
                        actor(operation), operation.getOperationId());
                if (resolved.found()) {
                    return acceptResolved(operation, resolved);
                }
            } catch (ConfigStateWriteException definitive) {
                throw definitive;
            } catch (RuntimeException resolveFailure) {
                submitFailure.addSuppressed(resolveFailure);
            }
            String message = "Config write commit is unknown; retry with the same "
                    + "X-Xuantong-Operation-Id: " + operation.getOperationId();
            operationRepository.updatePendingError(operation.getId(), message);
            commitUnknownTotal.incrementAndGet();
            throw new ConfigStateWriteException(message, true, submitFailure);
        }
    }

    private ConfigStateCommit acceptApplied(
            ConfigStateOperation operation, ApplyResult result) {
        if (!operation.getOperationId().equals(result.operationId())) {
            throw new IllegalStateException("Config State returned another operationId");
        }
        return acceptResult(
                operation, result.status(), result.resultType(), result.payload(), null);
    }

    private ConfigStateCommit acceptResolved(
            ConfigStateOperation operation, ResolvedConfigOperation result) {
        if (!MessageDigest.isEqual(
                operation.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                result.requestHash().getBytes(StandardCharsets.US_ASCII))) {
            fail(operation, "operationId was committed with another request");
        }
        return acceptResult(
                operation,
                result.status(),
                result.resultType(),
                result.payload(),
                result.requestHash());
    }

    private ConfigStateCommit acceptResult(
            ConfigStateOperation operation,
            ApplyStatus status,
            String resultType,
            byte[] payload,
            String ignoredRequestHash) {
        if (status == ApplyStatus.REJECTED) {
            String error = decodeMutationError(resultType, payload);
            fail(operation, error);
        }
        if (!ConfigStateCodec.RESULT_MUTATION.equals(resultType)) {
            fail(operation, "Unexpected Config State result: " + resultType);
        }
        try {
            ConfigMutationResult mutationResult = ConfigStateCodec.decodeMutationResult(payload);
            ConfigStateCommit commit = new ConfigStateCommit(
                    mutationResult.createdContentRevision(),
                    mutationResult.decision().decisionRevision(),
                    mutationResult.eventRevision());
            long markedCommitted = operationRepository.markCommitted(
                    operation.getId(),
                    commit.contentRevision(),
                    commit.decisionRevision(),
                    commit.eventRevision());
            if (markedCommitted == 1) {
                recordCommitted(operation);
            } else {
                ConfigStateOperation current = operationRepository.find(
                        operation.getTenant(),
                        operation.getPrincipal(),
                        operation.getOperationId());
                if (current == null || status(current) == ConfigStateOperationStatus.FAILED) {
                    throw new IllegalStateException(
                            "Failed to persist committed Config operation");
                }
            }
            return commit;
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Config State mutation result", e);
        }
    }

    private void fail(ConfigStateOperation operation, String message) {
        operationRepository.markFailed(operation.getId(), message);
        throw new ConfigStateWriteException(message, false);
    }

    private void recordCommitted(ConfigStateOperation operation) {
        ConfigStateOperationType type = ConfigStateOperationType.valueOf(
                operation.getOperationType());
        switch (type) {
            case PUBLISH -> publishCommittedTotal.incrementAndGet();
            case ROLLOUT_START -> rolloutStartedCommittedTotal.incrementAndGet();
            case ROLLOUT_PROMOTE -> rolloutPromotedCommittedTotal.incrementAndGet();
            case ROLLOUT_ABORT -> rolloutAbortedCommittedTotal.incrementAndGet();
            case ROLLBACK -> rollbackCommittedTotal.incrementAndGet();
            case TOMBSTONE -> tombstoneCommittedTotal.incrementAndGet();
        }
    }

    private String decodeMutationError(String resultType, byte[] payload) {
        if (!ConfigStateCodec.RESULT_MUTATION_ERROR.equals(resultType)) {
            return "Config State rejected the operation: " + resultType;
        }
        try {
            ConfigMutationError error = ConfigStateCodec.decodeMutationError(payload);
            return error.code() + ": " + error.message();
        } catch (IOException e) {
            return "Config State rejected the operation with a malformed error";
        }
    }

    private ConfigStateProjectionPlan releasePlan(
            ConfigResource resource,
            ConfigResourceKey key,
            String operator,
            ReleaseType releaseType,
            String auditOperation,
            long referencedContentRevision,
            ConfigRelease source) {
        ConfigStateProjectionPlan plan = new ConfigStateProjectionPlan();
        plan.setConfigId(resource.getId());
        plan.setNamespaceId(key.namespaceId());
        plan.setGroupName(key.groupName());
        plan.setDataId(key.dataId());
        plan.setOperator(requirePrincipal(operator));
        plan.setReleaseId(UUID.randomUUID().toString());
        plan.setReleaseType(releaseType.name());
        plan.setLifecycleStatus(ConfigLifecycleStatus.ACTIVE.name());
        plan.setContent(source == null ? resource.getContent() : source.getContent());
        plan.setContentType(source == null ? resource.getContentType() : source.getContentType());
        plan.setChecksum(source == null ? resource.getChecksum() : source.getChecksum());
        plan.setAuditOperation(auditOperation);
        plan.setBatch(false);
        plan.setReferencedContentRevision(referencedContentRevision);
        plan.setCreatedAtEpochMs(System.currentTimeMillis());
        return plan;
    }

    private void completeRolloutPlan(
            ConfigStateProjectionPlan plan,
            ConfigRollout rollout,
            RolloutStatus status) {
        plan.setRolloutAction(status == RolloutStatus.PROMOTED ? "PROMOTE" : "ABORT");
        plan.setRolloutId(rollout.getRolloutId());
        plan.setBaselineReleaseId(rollout.getBaselineReleaseId());
        plan.setCandidateReleaseId(rollout.getCandidateReleaseId());
        plan.setRolloutType(rollout.getRolloutType());
        plan.setTargetValue(rollout.getTargetValue());
        plan.setRolloutKey(rollout.getRolloutKey());
        plan.setRolloutStatus(status.name());
        plan.setRolloutCreatedBy(rollout.getCreatedBy());
        plan.setRolloutCreatedAtEpochMs(rollout.getCreatedAt().getTime());
    }

    private ConfigRelease requireStableProjection(
            ConfigResource resource, ReleaseDecision decision) {
        ConfigRelease baseline = releaseRepository.findLatestStable(resource.getId());
        if (baseline == null) {
            throw new IllegalStateException(
                    "Stable Config release projection is missing; wait for recovery");
        }
        requireContentRevision(baseline, decision.stableContentRevision(), "stable");
        return baseline;
    }

    private ConfigRelease requireRelease(
            ConfigResource resource, String releaseId, String role) {
        ConfigRelease release = releaseRepository.findByReleaseId(releaseId);
        if (release == null || !resource.getId().equals(release.getConfigId())) {
            throw new IllegalArgumentException(
                    "Config " + role + " release does not exist: " + releaseId);
        }
        return release;
    }

    private long requireContentRevision(
            ConfigRelease release, Long expected, String role) {
        Long revision = release.getContentRevision();
        if (revision == null || revision < 1) {
            throw new IllegalStateException(
                    "Config " + role + " release is not a Raft-backed 2.0 projection: "
                            + release.getReleaseId());
        }
        if (expected != null && revision.longValue() != expected.longValue()) {
            throw new IllegalStateException(
                    "Config " + role + " projection is stale; expected content revision "
                            + expected + " but found " + revision);
        }
        return revision;
    }

    private ConfigRollout requireActiveRollout(
            ConfigResource resource, String rolloutId) {
        ConfigRollout rollout = rolloutRepository.findActive(resource.getId());
        if (rollout == null || rolloutId == null || !rolloutId.equals(rollout.getRolloutId())) {
            throw new IllegalArgumentException("Active rollout does not exist: " + rolloutId);
        }
        return rollout;
    }

    private RolloutRule requireActiveRule(ReleaseDecision decision, String rolloutId) {
        return decision.rules().stream()
                .filter(rule -> rule.status() == RolloutRuleStatus.ACTIVE)
                .filter(rule -> rule.ruleId().equals(rolloutId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Config State has no active rollout rule: " + rolloutId));
    }

    private void ensureNoActiveDatabaseRollout(ConfigResource resource) {
        ConfigRollout active = rolloutRepository.findActive(resource.getId());
        if (active != null) {
            throw new IllegalStateException(
                    "Config has an active rollout; promote or abort it first: "
                            + active.getRolloutId());
        }
    }

    private void ensureNoUnfinishedOperation(ConfigResourceKey key) {
        ConfigStateOperation latest = operationRepository.findUnfinishedForConfig(
                key.namespaceId(), key.groupName(), key.dataId());
        if (latest == null) return;
        ConfigStateOperationStatus operationStatus = status(latest);
        throw new IllegalStateException(
                "Config has an unfinished State operation: "
                        + latest.getOperationId() + " (" + operationStatus + ")");
    }

    private void ensureNoActiveStateRollout(ReleaseDecision decision) {
        if (decision == null) return;
        decision.rules().stream()
                .filter(rule -> rule.status() == RolloutRuleStatus.ACTIVE)
                .findFirst()
                .ifPresent(rule -> {
                    throw new IllegalStateException(
                            "Config State has an active rollout: " + rule.ruleId());
                });
    }

    private ReleaseDecision requirePublishedDecision(ConfigResourceKey key) {
        ReleaseDecision decision = stateAccess.currentDecision(stateKey(key));
        if (decision == null) {
            throw new IllegalStateException(
                    "Publish a FULL release before this operation: " + key.canonicalName());
        }
        return decision;
    }

    private void requireActiveDecision(
            ConfigResourceKey key, ReleaseDecision decision) {
        if (!decision.active()) {
            throw new IllegalStateException(
                    "Publish or rollback an ACTIVE release before this operation: "
                            + key.canonicalName());
        }
    }

    private ConfigResource requireResource(ConfigResourceKey key) {
        ConfigResource resource = resourceRepository.find(key);
        if (resource == null) {
            throw new IllegalArgumentException(
                    "Config resource does not exist: " + key.canonicalName());
        }
        if (resource.getChecksum() == null || resource.getChecksum().isBlank()) {
            throw new IllegalStateException(
                    "Config draft checksum is missing: " + key.canonicalName());
        }
        return resource;
    }

    private ConfigContentDraft inlineContent(ConfigResource resource) {
        contentService.requireValid(resource.getContentType(), resource.getContent());
        return ConfigContentDraft.inline(
                resource.getContentType(),
                1,
                (resource.getContent() == null ? "" : resource.getContent())
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void validateStoredRequest(
            ConfigStateOperation operation,
            ConfigStateOperationType operationType,
            ConfigResourceKey key,
            ConfigStateProjectionPlan plan) {
        if (!operationType.name().equals(operation.getOperationType())
                || !key.namespaceId().equals(operation.getNamespaceId())
                || !key.groupName().equals(operation.getGroupName())
                || !key.dataId().equals(operation.getDataId())
                || !key.namespaceId().equals(plan.getNamespaceId())
                || !key.groupName().equals(plan.getGroupName())
                || !key.dataId().equals(plan.getDataId())) {
            throw operationConflict(operation.getOperationId());
        }
        StateCommand stored = storedCommand(operation);
        if (!MessageDigest.isEqual(
                operation.getRequestHash().getBytes(StandardCharsets.US_ASCII),
                ConfigStateCodec.requestHash(stored).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException(
                    "Stored Config operation payload hash is invalid: "
                            + operation.getOperationId());
        }
    }

    private StateCommand storedCommand(ConfigStateOperation operation) {
        return new StateCommand(
                stateAccess.groupId(),
                operation.getOperationId(),
                operation.getCommandType(),
                operation.getSchemaVersion(),
                Base64.getDecoder().decode(operation.getCommandPayload()));
    }

    private ConfigStateProjectionPlan decodePlan(ConfigStateOperation operation) {
        try {
            ConfigStateProjectionPlan plan = ONode.deserialize(
                    operation.getProjectionPayload(), ConfigStateProjectionPlan.class);
            if (plan == null || plan.getConfigId() == null || plan.getReleaseId() == null) {
                throw new IllegalStateException("Projection plan is incomplete");
            }
            return plan;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Malformed Config projection plan: " + operation.getOperationId(), e);
        }
    }

    private ConfigStateCommit committedRevisions(ConfigStateOperation operation) {
        if (operation.getContentRevision() == null
                || operation.getDecisionRevision() == null
                || operation.getEventRevision() == null) {
            throw new IllegalStateException(
                    "Committed Config operation has no revision result: "
                            + operation.getOperationId());
        }
        return new ConfigStateCommit(
                operation.getContentRevision(),
                operation.getDecisionRevision(),
                operation.getEventRevision());
    }

    private ConfigActor actor(ConfigResourceKey key, String operator) {
        return new ConfigActor(key.namespaceId(), requirePrincipal(operator));
    }

    private ConfigActor actor(ConfigStateOperation operation) {
        return new ConfigActor(operation.getTenant(), operation.getPrincipal());
    }

    private ConfigKey stateKey(ConfigResourceKey key) {
        return new ConfigKey(key.namespaceId(), key.groupName(), key.dataId());
    }

    private long decisionRevision(ReleaseDecision decision) {
        return decision == null ? 0 : decision.decisionRevision();
    }

    private void requireStateAvailable() {
        if (!stateAccess.available()) {
            throw new ConfigStateWriteException(
                    "Config State Plane is disabled or unavailable; 2.0 release writes require the authoritative Raft group",
                    false);
        }
    }

    private String requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException(
                    "X-Xuantong-Operation-Id header is required for Config writes");
        }
        String normalized = operationId.trim();
        if (normalized.length() > 128
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "X-Xuantong-Operation-Id must be 1-128 visible characters");
        }
        return normalized;
    }

    private String requirePrincipal(String operator) {
        if (operator == null || operator.isBlank()) {
            return "system";
        }
        return operator.trim();
    }

    private ConfigStateOperationStatus status(ConfigStateOperation operation) {
        try {
            return ConfigStateOperationStatus.valueOf(operation.getStatus());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid Config operation status: " + operation.getStatus(), e);
        }
    }

    private ConfigStateWriteException operationConflict(String operationId) {
        return new ConfigStateWriteException(
                "operationId was already used for another Config write: " + operationId,
                false);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
