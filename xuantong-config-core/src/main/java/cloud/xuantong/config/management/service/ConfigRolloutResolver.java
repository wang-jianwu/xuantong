package cloud.xuantong.config.management.service;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

/** Resolves the release visible to one concrete client instance. */
@Slf4j
@Component
public class ConfigRolloutResolver {
    @Inject
    private ConfigResourceRepository resourceRepository;
    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private ConfigRolloutRepository rolloutRepository;

    public ConfigRelease resolve(
            String namespaceId,
            String groupName,
            String dataId,
            String clientInstanceId,
            String clientIp) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = resourceRepository.find(key);
        if (resource == null) return null;

        ConfigRelease stable = releaseRepository.findLatestStable(resource.getId());
        ConfigRollout active = rolloutRepository.findActive(resource.getId());
        if (active == null) return stable;

        ConfigRelease baseline = releaseRepository.findByReleaseId(active.getBaselineReleaseId());
        if (baseline == null) {
            log.error("Rollout baseline is missing; using latest stable release: rolloutId={}, resource={}",
                    active.getRolloutId(), key.canonicalName());
            baseline = stable;
        }
        ConfigRelease candidate = releaseRepository.findByReleaseId(active.getCandidateReleaseId());
        if (candidate == null) {
            log.error("Rollout candidate is missing; using baseline release: rolloutId={}, resource={}",
                    active.getRolloutId(), key.canonicalName());
            return baseline;
        }

        try {
            ConfigRolloutPolicy policy = ConfigRolloutPolicy.restore(active);
            return policy.matches(active.getRolloutId(), clientInstanceId, clientIp)
                    ? candidate
                    : baseline;
        } catch (RuntimeException e) {
            log.error("Invalid rollout policy; using baseline release: rolloutId={}, resource={}",
                    active.getRolloutId(), key.canonicalName(), e);
            return baseline;
        }
    }
}
