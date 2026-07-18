package cloud.xuantong.config.management.service;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.config.management.model.RolloutStatus;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.annotation.Transaction;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the explicit start/promote/abort lifecycle of a gray release. */
@Component
public class ConfigRolloutService {
    private final AtomicLong startedTotal = new AtomicLong();
    private final AtomicLong promotedTotal = new AtomicLong();
    private final AtomicLong abortedTotal = new AtomicLong();

    @Inject
    private ConfigResourceRepository resourceRepository;
    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private ConfigRolloutRepository rolloutRepository;
    @Inject
    private ConfigReleaseManager releaseManager;

    public List<ConfigRollout> findRollouts(String namespaceId, String groupName, String dataId) {
        ConfigResource resource = resourceRepository.find(
                ConfigResourceKey.of(namespaceId, groupName, dataId));
        return resource == null ? List.of() : rolloutRepository.findByConfigId(resource.getId());
    }

    @Transaction
    public ConfigRolloutMutation start(
            String namespaceId,
            String groupName,
            String dataId,
            ConfigRolloutPolicy policy,
            String operator) {
        if (policy == null) throw new IllegalArgumentException("Rollout policy must not be null");
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = requireResource(key);
        ensureNoActive(resource);
        ConfigRelease baseline = releaseRepository.findLatestStable(resource.getId());
        if (baseline == null) {
            throw new IllegalStateException("Publish a FULL release before starting a gray rollout: "
                    + key.canonicalName());
        }

        String rolloutId = UUID.randomUUID().toString();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rolloutId", rolloutId);
        detail.put("baselineReleaseId", baseline.getReleaseId());
        detail.put("rolloutType", policy.type().name());
        detail.put("targetValue", policy.targetValue());
        ConfigRelease candidate = releaseManager.create(resource, key, policy.type(), operator,
                "CONFIG_ROLLOUT_STARTED", false,
                resource.getContent(), resource.getContentType(), resource.getChecksum(), detail);

        ConfigRollout rollout = new ConfigRollout();
        rollout.setRolloutId(rolloutId);
        rollout.setConfigId(resource.getId());
        rollout.setNamespaceId(key.namespaceId());
        rollout.setGroupName(key.groupName());
        rollout.setDataId(key.dataId());
        rollout.setBaselineReleaseId(baseline.getReleaseId());
        rollout.setCandidateReleaseId(candidate.getReleaseId());
        rollout.setRolloutType(policy.type().name());
        rollout.setTargetValue(policy.targetValue());
        rollout.setStatus(RolloutStatus.ACTIVE.name());
        rollout.setCreatedBy(operator);
        rollout.setCreatedAt(new Date());
        if (rolloutRepository.save(rollout) != 1) {
            throw new IllegalStateException("Failed to save config rollout: " + key.canonicalName());
        }
        startedTotal.incrementAndGet();
        return new ConfigRolloutMutation(rollout, candidate);
    }

    @Transaction
    public ConfigRolloutMutation promote(
            String namespaceId, String groupName, String dataId, String rolloutId, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = requireResource(key);
        ConfigRollout rollout = requireActive(resource, rolloutId);
        ConfigRelease candidate = requireRelease(rollout.getCandidateReleaseId(), "candidate");
        ConfigRelease promoted = releaseManager.create(resource, key, ReleaseType.FULL, operator,
                "CONFIG_ROLLOUT_PROMOTED", false,
                candidate.getContent(), candidate.getContentType(), candidate.getChecksum(),
                Map.of("rolloutId", rolloutId,
                        "candidateReleaseId", candidate.getReleaseId()));
        complete(rollout, RolloutStatus.PROMOTED, operator);
        promotedTotal.incrementAndGet();
        return new ConfigRolloutMutation(rollout, promoted);
    }

    @Transaction
    public ConfigRolloutMutation abort(
            String namespaceId, String groupName, String dataId, String rolloutId, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = requireResource(key);
        ConfigRollout rollout = requireActive(resource, rolloutId);
        ConfigRelease baseline = requireRelease(rollout.getBaselineReleaseId(), "baseline");
        ConfigRelease restored = releaseManager.create(resource, key, ReleaseType.ROLLBACK, operator,
                "CONFIG_ROLLOUT_ABORTED", false,
                baseline.getContent(), baseline.getContentType(), baseline.getChecksum(),
                Map.of("rolloutId", rolloutId,
                        "baselineReleaseId", baseline.getReleaseId()));
        complete(rollout, RolloutStatus.ABORTED, operator);
        abortedTotal.incrementAndGet();
        return new ConfigRolloutMutation(rollout, restored);
    }

    public long startedTotal() { return startedTotal.get(); }
    public long promotedTotal() { return promotedTotal.get(); }
    public long abortedTotal() { return abortedTotal.get(); }

    private ConfigResource requireResource(ConfigResourceKey key) {
        ConfigResource resource = resourceRepository.find(key);
        if (resource == null) {
            throw new IllegalArgumentException("Config resource does not exist: " + key.canonicalName());
        }
        return resource;
    }

    private void ensureNoActive(ConfigResource resource) {
        ConfigRollout active = rolloutRepository.findActive(resource.getId());
        if (active != null) {
            throw new IllegalStateException("Config has an active rollout; promote or abort it first: "
                    + active.getRolloutId());
        }
    }

    private ConfigRollout requireActive(ConfigResource resource, String rolloutId) {
        ConfigRollout active = rolloutRepository.findActive(resource.getId());
        if (active == null || rolloutId == null || !rolloutId.equals(active.getRolloutId())) {
            throw new IllegalArgumentException("Active rollout does not exist: " + rolloutId);
        }
        return active;
    }

    private ConfigRelease requireRelease(String releaseId, String role) {
        ConfigRelease release = releaseRepository.findByReleaseId(releaseId);
        if (release == null) {
            throw new IllegalStateException("Rollout " + role + " release does not exist: " + releaseId);
        }
        return release;
    }

    private void complete(ConfigRollout rollout, RolloutStatus status, String operator) {
        if (rolloutRepository.complete(rollout.getRolloutId(), RolloutStatus.ACTIVE, status, operator) != 1) {
            throw new IllegalStateException("Config rollout was modified concurrently: "
                    + rollout.getRolloutId());
        }
        rollout.setStatus(status.name());
        rollout.setCompletedBy(operator);
        rollout.setCompletedAt(new Date());
    }
}
