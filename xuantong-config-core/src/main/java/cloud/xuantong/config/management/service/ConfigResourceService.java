package cloud.xuantong.config.management.service;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.resource.model.ConfigNamespace;
import cloud.xuantong.config.management.content.ConfigContentService;
import cloud.xuantong.config.management.content.ConfigContentType;
import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.config.management.model.ConfigLifecycleStatus;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.resource.model.ResourceNameRules;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.AuditLogRepository;
import cloud.xuantong.config.management.repository.AuditLogFilter;
import cloud.xuantong.resource.repository.NamespaceRepository;
import cloud.xuantong.resource.repository.ResourceGroupRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.annotation.Transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ConfigResourceService {
    private final AtomicLong publishTotal = new AtomicLong();
    private final AtomicLong rollbackTotal = new AtomicLong();
    private final AtomicLong batchTotal = new AtomicLong();
    private final AtomicLong batchReleaseTotal = new AtomicLong();

    @Inject
    private ConfigResourceRepository resourceRepository;
    @Inject
    private NamespaceRepository namespaceRepository;
    @Inject
    private ResourceGroupRepository groupRepository;
    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private ConfigRolloutRepository rolloutRepository;
    @Inject
    private AuditLogRepository auditLogRepository;
    @Inject
    private ConfigReleaseManager releaseManager;
    @Inject
    private ConfigContentService contentService;

    public ConfigResource find(String namespaceId, String groupName, String dataId) {
        return resourceRepository.find(ConfigResourceKey.of(namespaceId, groupName, dataId));
    }

    public List<ConfigResource> findByGroup(String namespaceId, String groupName) {
        return resourceRepository.findByGroup(
                ResourceNameRules.validate("namespaceId", namespaceId),
                ResourceNameRules.validate("groupName", groupName));
    }

    public PageResult<ConfigResource> findPage(
            String namespaceId,
            String groupName,
            String keyword,
            String lifecycleStatus,
            PageQuery pageQuery) {
        return resourceRepository.findPage(
                ResourceNameRules.validate("namespaceId", namespaceId),
                ResourceNameRules.validate("groupName", groupName),
                keyword,
                lifecycleStatus,
                pageQuery);
    }

    @Transaction
    public ConfigResource saveDraft(
            ConfigResource draft, Long expectedDraftRevision, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(
                draft.getNamespaceId(), draft.getGroupName(), draft.getDataId());
        ConfigNamespace namespace = namespaceRepository.findByNamespaceId(key.namespaceId());
        if (namespace == null || !Boolean.TRUE.equals(namespace.getIsActive())) {
            throw new IllegalArgumentException("Namespace is missing or inactive: " + key.namespaceId());
        }
        if (groupRepository.find(key.namespaceId(), key.groupName()) == null) {
            throw new IllegalArgumentException("Group does not exist: " + key.groupName());
        }

        String contentType = ConfigContentType.parse(draft.getContentType()).wireName();
        contentService.requireValid(contentType, draft.getContent());

        draft.setNamespaceId(key.namespaceId());
        draft.setGroupName(key.groupName());
        draft.setDataId(key.dataId());
        draft.setContentType(contentType);
        draft.setChecksum(checksum(draft.getContent()));
        draft.setUpdatedBy(operator);
        draft.setUpdatedAt(new Date());

        ConfigResource existing = resourceRepository.find(key);
        if (existing == null) {
            long expected = expectedDraftRevision == null ? 0L : expectedDraftRevision;
            if (expected != 0L) {
                throw new ConfigDraftConflictException(expected, 0L, null, draft);
            }
            draft.setRevision(0L);
            draft.setDraftRevision(1L);
            draft.setLifecycleStatus(ConfigLifecycleStatus.DRAFT.name());
            draft.setCreatedBy(operator);
            draft.setCreatedAt(new Date());
            resourceRepository.save(draft);
        } else {
            ensureNoActiveRollout(existing);
            long actualDraftRevision = draftRevision(existing);
            long expected = expectedDraftRevision == null ? -1L : expectedDraftRevision;
            if (expected != actualDraftRevision) {
                throw new ConfigDraftConflictException(
                        expected, actualDraftRevision, existing, draft);
            }
            draft.setId(existing.getId());
            draft.setRevision(existing.getRevision());
            draft.setDraftRevision(actualDraftRevision + 1L);
            draft.setLifecycleStatus(existing.getLifecycleStatus());
            draft.setCreatedBy(existing.getCreatedBy());
            draft.setCreatedAt(existing.getCreatedAt());
            if (resourceRepository.updateDraft(draft, actualDraftRevision) != 1) {
                ConfigResource current = resourceRepository.find(key);
                throw new ConfigDraftConflictException(
                        expected,
                        current == null ? actualDraftRevision : draftRevision(current),
                        current,
                        draft);
            }
        }
        return draft;
    }

    public boolean deleteDraft(String namespaceId, String groupName, String dataId) {
        return resourceRepository.deleteUnpublishedDraft(
                ConfigResourceKey.of(namespaceId, groupName, dataId)) > 0;
    }

    public List<ConfigRelease> findReleases(String namespaceId, String groupName, String dataId) {
        ConfigResource resource = find(namespaceId, groupName, dataId);
        return resource == null ? List.of() : releaseRepository.findByConfigId(resource.getId());
    }

    public PageResult<ConfigRelease> findReleasePage(
            String namespaceId,
            String groupName,
            String dataId,
            PageQuery pageQuery) {
        ConfigResource resource = find(namespaceId, groupName, dataId);
        return resource == null
                ? PageResult.of(pageQuery, 0, List.of())
                : releaseRepository.findPageByConfigId(resource.getId(), pageQuery);
    }

    public List<AuditLog> findAudits(String namespaceId, String groupName, String dataId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        return auditLogRepository.findByResource(
                key.namespaceId(), key.groupName(), "CONFIG", key.dataId());
    }

    public PageResult<AuditLog> findAuditPage(
            String namespaceId,
            String groupName,
            String dataId,
            PageQuery pageQuery) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        return auditLogRepository.findPage(AuditLogFilter.resource(
                key.namespaceId(), key.groupName(), "CONFIG", key.dataId()), pageQuery);
    }

    @Transaction
    public ConfigRelease publish(
            String namespaceId, String groupName, String dataId, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = resourceRepository.find(key);
        if (resource == null) {
            throw new IllegalArgumentException("Config resource does not exist: " + key.canonicalName());
        }
        ensureNoActiveRollout(resource);
        contentService.requireValid(resource.getContentType(), resource.getContent());

        ConfigRelease release = releaseManager.create(resource, key, ReleaseType.FULL,
                operator, "CONFIG_PUBLISHED", false,
                resource.getContent(), resource.getContentType(), resource.getChecksum(), Map.of());
        publishTotal.incrementAndGet();
        return release;
    }

    @Transaction
    public List<ConfigRelease> publishBatch(
            String namespaceId,
            String groupName,
            List<String> dataIds,
            String operator) {
        String normalizedNamespace = ResourceNameRules.validate("namespaceId", namespaceId);
        String normalizedGroup = ResourceNameRules.validate("groupName", groupName);
        if (dataIds == null || dataIds.isEmpty()) {
            throw new IllegalArgumentException("dataIds must not be empty");
        }
        if (dataIds.size() > 100) {
            throw new IllegalArgumentException("A batch can publish at most 100 configs");
        }

        Set<String> uniqueDataIds = new HashSet<>();
        List<ConfigResource> resources = new ArrayList<>(dataIds.size());
        List<ConfigResourceKey> keys = new ArrayList<>(dataIds.size());
        for (String dataId : dataIds) {
            ConfigResourceKey key = ConfigResourceKey.of(normalizedNamespace, normalizedGroup, dataId);
            if (!uniqueDataIds.add(key.dataId())) {
                throw new IllegalArgumentException("Duplicate dataId in batch: " + key.dataId());
            }
            ConfigResource resource = resourceRepository.find(key);
            if (resource == null) {
                throw new IllegalArgumentException(
                        "Config resource does not exist: " + key.canonicalName());
            }
            ensureNoActiveRollout(resource);
            contentService.requireValid(resource.getContentType(), resource.getContent());
            keys.add(key);
            resources.add(resource);
        }

        List<ConfigRelease> releases = new ArrayList<>(resources.size());
        for (int i = 0; i < resources.size(); i++) {
            ConfigResource resource = resources.get(i);
            releases.add(releaseManager.create(resource, keys.get(i), ReleaseType.FULL,
                    operator, "CONFIG_BATCH_PUBLISHED", true,
                    resource.getContent(), resource.getContentType(), resource.getChecksum(), Map.of()));
        }
        batchTotal.incrementAndGet();
        batchReleaseTotal.addAndGet(releases.size());
        return List.copyOf(releases);
    }

    @Transaction
    public ConfigRelease rollback(
            String namespaceId, String groupName, String dataId, String releaseId, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = resourceRepository.find(key);
        if (resource == null) {
            throw new IllegalArgumentException("Config resource does not exist: " + key.canonicalName());
        }
        ensureNoActiveRollout(resource);
        ConfigRelease target = releaseRepository.findByReleaseId(releaseId);
        if (target == null || !resource.getId().equals(target.getConfigId())) {
            throw new IllegalArgumentException("Release does not belong to config resource: " + releaseId);
        }

        resource.setContent(target.getContent());
        resource.setContentType(target.getContentType());
        resource.setChecksum(target.getChecksum());
        resource.setUpdatedBy(operator);
        resource.setUpdatedAt(new Date());
        long expectedDraftRevision = draftRevision(resource);
        if (resourceRepository.updateDraft(resource, expectedDraftRevision) != 1) {
            throw new IllegalStateException("Failed to restore release content: " + releaseId);
        }
        resource.setDraftRevision(expectedDraftRevision + 1L);
        ConfigRelease release = releaseManager.create(resource, key, ReleaseType.ROLLBACK, operator,
                "CONFIG_ROLLED_BACK", false,
                resource.getContent(), resource.getContentType(), resource.getChecksum(),
                Map.of("targetReleaseId", releaseId));
        rollbackTotal.incrementAndGet();
        return release;
    }

    public long publishTotal() { return publishTotal.get(); }
    public long rollbackTotal() { return rollbackTotal.get(); }
    public long batchTotal() { return batchTotal.get(); }
    public long batchReleaseTotal() { return batchReleaseTotal.get(); }

    private void ensureNoActiveRollout(ConfigResource resource) {
        ConfigRollout active = rolloutRepository.findActive(resource.getId());
        if (active != null) {
            throw new IllegalStateException("Config has an active rollout; promote or abort it first: "
                    + active.getRolloutId());
        }
    }

    private String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private long draftRevision(ConfigResource resource) {
        return resource.getDraftRevision() == null ? 0L : resource.getDraftRevision();
    }
}
