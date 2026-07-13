package cloud.xuantong.core;

import cloud.xuantong.core.v2.model.ConfigNamespace;
import cloud.xuantong.core.v2.model.AuditLog;
import cloud.xuantong.core.v2.model.ConfigRelease;
import cloud.xuantong.core.v2.model.ConfigResource;
import cloud.xuantong.core.v2.model.ConfigResourceKey;
import cloud.xuantong.core.v2.model.ReleaseType;
import cloud.xuantong.core.v2.model.ResourceGroup;
import cloud.xuantong.core.v2.repository.ConfigReleaseRepository;
import cloud.xuantong.core.v2.repository.ConfigResourceRepository;
import cloud.xuantong.core.v2.repository.AuditLogRepository;
import cloud.xuantong.core.v2.repository.NamespaceRepository;
import cloud.xuantong.core.v2.repository.ResourceGroupRepository;
import cloud.xuantong.core.v2.service.ConfigResourceServiceV2;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigResourceServiceV2Test {
    @Test
    void savesDraftAndCreatesImmutableRelease() throws Exception {
        InMemoryConfigRepository configs = new InMemoryConfigRepository();
        InMemoryReleaseRepository releases = new InMemoryReleaseRepository();
        InMemoryAuditRepository audits = new InMemoryAuditRepository();
        ConfigResourceServiceV2 service = new ConfigResourceServiceV2();
        inject(service, "resourceRepository", configs);
        inject(service, "releaseRepository", releases);
        inject(service, "auditLogRepository", audits);
        inject(service, "namespaceRepository", activeNamespaceRepository());
        inject(service, "groupRepository", defaultGroupRepository());

        ConfigResource draft = new ConfigResource();
        draft.setNamespaceId("public");
        draft.setGroupName("DEFAULT_GROUP");
        draft.setDataId("application.yml");
        draft.setContent("server:\n  port: 8080");
        draft.setContentType("yaml");

        ConfigResource saved = service.saveDraft(draft, "admin");
        assertNotNull(saved.getId());
        assertEquals(0L, saved.getRevision());

        ConfigRelease release = service.publish(
                "public", "DEFAULT_GROUP", "application.yml", ReleaseType.FULL, "admin");

        assertEquals(1L, release.getRevision());
        assertEquals(saved.getContent(), release.getContent());
        assertEquals(saved.getChecksum(), release.getChecksum());
        assertEquals(1L, configs.resource.getRevision());
        assertEquals(1, releases.items.size());

        ConfigResource changedDraft = new ConfigResource();
        changedDraft.setNamespaceId("public");
        changedDraft.setGroupName("DEFAULT_GROUP");
        changedDraft.setDataId("application.yml");
        changedDraft.setContent("server:\n  port: 9090");
        changedDraft.setContentType("yaml");
        service.saveDraft(changedDraft, "admin");
        service.publish("public", "DEFAULT_GROUP", "application.yml", ReleaseType.FULL, "admin");

        ConfigRelease rollback = service.rollback(
                "public", "DEFAULT_GROUP", "application.yml", release.getReleaseId(), "admin");
        assertEquals(3L, rollback.getRevision());
        assertEquals(ReleaseType.ROLLBACK.name(), rollback.getReleaseType());
        assertEquals("server:\n  port: 8080", rollback.getContent());
        assertEquals(3L, configs.resource.getRevision());
        assertEquals(3, releases.items.size());
        assertEquals(3, audits.items.size());
        assertEquals("CONFIG_ROLLED_BACK", audits.items.get(2).getOperation());
        assertEquals(2L, service.publishTotal());
        assertEquals(1L, service.rollbackTotal());
    }

    @Test
    void batchPublishValidatesEverythingBeforeWriting() throws Exception {
        BatchConfigRepository configs = new BatchConfigRepository();
        configs.add(resource(1L, "a.yml"));
        configs.add(resource(2L, "b.yml"));
        InMemoryReleaseRepository releases = new InMemoryReleaseRepository();
        InMemoryAuditRepository audits = new InMemoryAuditRepository();
        ConfigResourceServiceV2 service = service(configs, releases, audits);

        List<ConfigRelease> batch = service.publishBatch(
                "public", "DEFAULT_GROUP", List.of("a.yml", "b.yml"), ReleaseType.FULL, "admin");

        assertEquals(2, batch.size());
        assertEquals(1L, configs.resources.get("a.yml").getRevision());
        assertEquals(1L, configs.resources.get("b.yml").getRevision());
        assertEquals(2, releases.items.size());
        assertEquals(2, audits.items.size());
        assertEquals("CONFIG_BATCH_PUBLISHED", audits.items.get(0).getOperation());
        assertEquals(1L, service.batchTotal());
        assertEquals(2L, service.batchReleaseTotal());

        BatchConfigRepository incomplete = new BatchConfigRepository();
        incomplete.add(resource(3L, "only.yml"));
        InMemoryReleaseRepository noReleases = new InMemoryReleaseRepository();
        InMemoryAuditRepository noAudits = new InMemoryAuditRepository();
        ConfigResourceServiceV2 incompleteService = service(incomplete, noReleases, noAudits);

        assertThrows(IllegalArgumentException.class, () -> incompleteService.publishBatch(
                "public", "DEFAULT_GROUP", List.of("only.yml", "missing.yml"),
                ReleaseType.FULL, "admin"));
        assertEquals(0L, incomplete.resources.get("only.yml").getRevision());
        assertEquals(0, noReleases.items.size());
        assertEquals(0, noAudits.items.size());
    }

    private ConfigResourceServiceV2 service(
            ConfigResourceRepository configs,
            ConfigReleaseRepository releases,
            AuditLogRepository audits) throws Exception {
        ConfigResourceServiceV2 service = new ConfigResourceServiceV2();
        inject(service, "resourceRepository", configs);
        inject(service, "releaseRepository", releases);
        inject(service, "auditLogRepository", audits);
        inject(service, "namespaceRepository", activeNamespaceRepository());
        inject(service, "groupRepository", defaultGroupRepository());
        return service;
    }

    private ConfigResource resource(Long id, String dataId) {
        ConfigResource resource = new ConfigResource();
        resource.setId(id);
        resource.setNamespaceId("public");
        resource.setGroupName("DEFAULT_GROUP");
        resource.setDataId(dataId);
        resource.setContent("name: " + dataId);
        resource.setContentType("yaml");
        resource.setChecksum("checksum-" + dataId);
        resource.setRevision(0L);
        return resource;
    }

    private NamespaceRepository activeNamespaceRepository() {
        return new NamespaceRepository() {
            @Override public List<ConfigNamespace> findAll() { return List.of(findByNamespaceId("public")); }
            @Override public ConfigNamespace findByNamespaceId(String namespaceId) {
                ConfigNamespace namespace = new ConfigNamespace();
                namespace.setNamespaceId(namespaceId);
                namespace.setIsActive(true);
                return namespace;
            }
            @Override public long save(ConfigNamespace namespace) { return 1; }
            @Override public long update(ConfigNamespace namespace) { return 1; }
        };
    }

    private ResourceGroupRepository defaultGroupRepository() {
        return new ResourceGroupRepository() {
            @Override public List<ResourceGroup> findByNamespace(String namespaceId) { return List.of(find(namespaceId, "DEFAULT_GROUP")); }
            @Override public ResourceGroup find(String namespaceId, String groupName) {
                ResourceGroup group = new ResourceGroup();
                group.setNamespaceId(namespaceId);
                group.setGroupName(groupName);
                return group;
            }
            @Override public long save(ResourceGroup group) { return 1; }
        };
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class InMemoryConfigRepository implements ConfigResourceRepository {
        private ConfigResource resource;

        @Override public ConfigResource find(ConfigResourceKey key) { return resource; }
        @Override public List<ConfigResource> findByGroup(String namespaceId, String groupName) {
            return resource == null ? List.of() : List.of(resource);
        }
        @Override public long save(ConfigResource resource) {
            resource.setId(1L);
            this.resource = resource;
            return 1;
        }
        @Override public long updateDraft(ConfigResource resource) { this.resource = resource; return 1; }
        @Override public long updateRevision(Long configId, long expectedRevision, long newRevision) {
            if (resource == null || !resource.getId().equals(configId) || resource.getRevision() != expectedRevision) {
                return 0;
            }
            resource.setRevision(newRevision);
            return 1;
        }
        @Override public long deleteUnpublishedDraft(ConfigResourceKey key) { resource = null; return 1; }
    }

    private static class InMemoryReleaseRepository implements ConfigReleaseRepository {
        private final List<ConfigRelease> items = new ArrayList<>();
        @Override public long save(ConfigRelease release) { items.add(release); return 1; }
        @Override public ConfigRelease findByReleaseId(String releaseId) {
            return items.stream().filter(o -> o.getReleaseId().equals(releaseId)).findFirst().orElse(null);
        }
        @Override public ConfigRelease findLatest(String namespaceId, String groupName, String dataId) {
            return items.stream()
                    .filter(o -> o.getNamespaceId().equals(namespaceId)
                            && o.getGroupName().equals(groupName)
                            && o.getDataId().equals(dataId))
                    .max(java.util.Comparator.comparing(ConfigRelease::getRevision))
                    .orElse(null);
        }
        @Override public List<ConfigRelease> findByConfigId(Long configId) {
            return items.stream().filter(o -> o.getConfigId().equals(configId)).toList();
        }
    }

    private static class InMemoryAuditRepository implements AuditLogRepository {
        private final List<AuditLog> items = new ArrayList<>();

        @Override public long save(AuditLog auditLog) {
            auditLog.setId((long) items.size() + 1);
            items.add(auditLog);
            return 1;
        }

        @Override public List<AuditLog> findByResource(
                String namespaceId, String groupName, String resourceType, String resourceName) {
            return items.stream()
                    .filter(item -> namespaceId.equals(item.getNamespaceId())
                            && groupName.equals(item.getGroupName())
                            && resourceType.equals(item.getResourceType())
                            && resourceName.equals(item.getResourceName()))
                    .toList();
        }

        @Override public List<AuditLog> findRecent(int limit) {
            int safeLimit = Math.max(1, limit);
            return items.size() <= safeLimit
                    ? List.copyOf(items)
                    : List.copyOf(items.subList(0, safeLimit));
        }
    }

    private static class BatchConfigRepository implements ConfigResourceRepository {
        private final Map<String, ConfigResource> resources = new HashMap<>();

        void add(ConfigResource resource) {
            resources.put(resource.getDataId(), resource);
        }

        @Override public ConfigResource find(ConfigResourceKey key) { return resources.get(key.dataId()); }
        @Override public List<ConfigResource> findByGroup(String namespaceId, String groupName) {
            return new ArrayList<>(resources.values());
        }
        @Override public long save(ConfigResource resource) { add(resource); return 1; }
        @Override public long updateDraft(ConfigResource resource) { add(resource); return 1; }
        @Override public long updateRevision(Long configId, long expectedRevision, long newRevision) {
            ConfigResource resource = resources.values().stream()
                    .filter(item -> item.getId().equals(configId))
                    .findFirst()
                    .orElse(null);
            if (resource == null || resource.getRevision() != expectedRevision) return 0;
            resource.setRevision(newRevision);
            return 1;
        }
        @Override public long deleteUnpublishedDraft(ConfigResourceKey key) {
            return resources.remove(key.dataId()) == null ? 0 : 1;
        }
    }
}
