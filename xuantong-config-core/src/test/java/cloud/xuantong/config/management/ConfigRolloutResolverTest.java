package cloud.xuantong.config.management;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.config.management.model.RolloutStatus;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.management.service.ConfigRolloutResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigRolloutResolverTest {
    @Test
    void resolvesCandidateOnlyForMatchingClientAndFallsBackToStable() throws Exception {
        ConfigResource resource = new ConfigResource();
        resource.setId(1L);
        resource.setNamespaceId("public");
        resource.setGroupName("DEFAULT_GROUP");
        resource.setDataId("app.yml");

        ReleaseRepository releases = new ReleaseRepository();
        ConfigRelease baseline = release("baseline", 1L, ReleaseType.FULL, "v1");
        ConfigRelease candidate = release("candidate", 2L, ReleaseType.GRAY_IP, "v2");
        releases.items.add(baseline);
        releases.items.add(candidate);

        ConfigRollout rollout = new ConfigRollout();
        rollout.setRolloutId("rollout-a");
        rollout.setConfigId(1L);
        rollout.setBaselineReleaseId("baseline");
        rollout.setCandidateReleaseId("candidate");
        rollout.setRolloutType(ReleaseType.GRAY_IP.name());
        rollout.setTargetValue("10.0.0.8");
        rollout.setStatus(RolloutStatus.ACTIVE.name());
        RolloutRepository rollouts = new RolloutRepository(rollout);

        ConfigRolloutResolver resolver = new ConfigRolloutResolver();
        inject(resolver, "resourceRepository", new ResourceRepository(resource));
        inject(resolver, "releaseRepository", releases);
        inject(resolver, "rolloutRepository", rollouts);

        assertEquals("v2", resolver.resolve(
                "public", "DEFAULT_GROUP", "app.yml", "client-a", "10.0.0.8").getContent());
        assertEquals("v1", resolver.resolve(
                "public", "DEFAULT_GROUP", "app.yml", "client-b", "10.0.0.9").getContent());

        rollout.setStatus(RolloutStatus.ABORTED.name());
        assertEquals("v1", resolver.resolve(
                "public", "DEFAULT_GROUP", "app.yml", "client-a", "10.0.0.8").getContent());
    }

    private ConfigRelease release(String id, long revision, ReleaseType type, String content) {
        ConfigRelease release = new ConfigRelease();
        release.setReleaseId(id);
        release.setConfigId(1L);
        release.setNamespaceId("public");
        release.setGroupName("DEFAULT_GROUP");
        release.setDataId("app.yml");
        release.setRevision(revision);
        release.setReleaseType(type.name());
        release.setContent(content);
        return release;
    }

    private void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record ResourceRepository(ConfigResource resource) implements ConfigResourceRepository {
        @Override public ConfigResource find(ConfigResourceKey key) { return resource; }
        @Override public List<ConfigResource> findByGroup(String namespaceId, String groupName) { return List.of(resource); }
        @Override public long save(ConfigResource resource) { return 1; }
        @Override public long updateDraft(ConfigResource resource) { return 1; }
        @Override public long updateRevision(
                Long configId, long expectedRevision, String expectedChecksum, long newRevision) { return 1; }
        @Override public long deleteUnpublishedDraft(ConfigResourceKey key) { return 0; }
    }

    private static class ReleaseRepository implements ConfigReleaseRepository {
        private final List<ConfigRelease> items = new ArrayList<>();
        @Override public long save(ConfigRelease release) { items.add(release); return 1; }
        @Override public ConfigRelease findByReleaseId(String releaseId) {
            return items.stream().filter(item -> releaseId.equals(item.getReleaseId())).findFirst().orElse(null);
        }
        @Override public ConfigRelease findLatestStable(Long configId) {
            return items.stream().filter(item -> !item.getReleaseType().startsWith("GRAY_"))
                    .max(java.util.Comparator.comparing(ConfigRelease::getRevision)).orElse(null);
        }
        @Override public List<ConfigRelease> findByConfigId(Long configId) { return List.copyOf(items); }
    }

    private static class RolloutRepository implements ConfigRolloutRepository {
        private final ConfigRollout rollout;
        private RolloutRepository(ConfigRollout rollout) { this.rollout = rollout; }
        @Override public long save(ConfigRollout rollout) { return 1; }
        @Override public ConfigRollout findActive(Long configId) {
            return RolloutStatus.ACTIVE.name().equals(rollout.getStatus()) ? rollout : null;
        }
        @Override public ConfigRollout findByRolloutId(String rolloutId) { return rollout; }
        @Override public List<ConfigRollout> findByConfigId(Long configId) { return List.of(rollout); }
        @Override public long complete(String rolloutId, RolloutStatus expectedStatus,
                                       RolloutStatus newStatus, String operator) { return 1; }
    }
}
