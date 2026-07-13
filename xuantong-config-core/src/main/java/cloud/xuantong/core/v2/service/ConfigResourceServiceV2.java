package cloud.xuantong.core.v2.service;

import cloud.xuantong.core.v2.model.ConfigNamespace;
import cloud.xuantong.core.v2.model.AuditLog;
import cloud.xuantong.core.v2.model.ConfigResource;
import cloud.xuantong.core.v2.model.ConfigResourceKey;
import cloud.xuantong.core.v2.model.ConfigRelease;
import cloud.xuantong.core.v2.model.ReleaseType;
import cloud.xuantong.core.v2.model.ResourceNameRules;
import cloud.xuantong.core.v2.repository.ConfigReleaseRepository;
import cloud.xuantong.core.v2.repository.ConfigResourceRepository;
import cloud.xuantong.core.v2.repository.AuditLogRepository;
import cloud.xuantong.core.v2.repository.NamespaceRepository;
import cloud.xuantong.core.v2.repository.ResourceGroupRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.annotation.Transaction;
import org.noear.snack4.ONode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ConfigResourceServiceV2 {
    private static final Set<String> CONTENT_TYPES = Set.of("text", "properties", "yaml", "json", "xml");
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
    private AuditLogRepository auditLogRepository;

    public ConfigResource find(String namespaceId, String groupName, String dataId) {
        return resourceRepository.find(ConfigResourceKey.of(namespaceId, groupName, dataId));
    }

    public List<ConfigResource> findByGroup(String namespaceId, String groupName) {
        return resourceRepository.findByGroup(
                ResourceNameRules.validate("namespaceId", namespaceId),
                ResourceNameRules.validate("groupName", groupName));
    }

    @Transaction
    public ConfigResource saveDraft(ConfigResource draft, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(
                draft.getNamespaceId(), draft.getGroupName(), draft.getDataId());
        ConfigNamespace namespace = namespaceRepository.findByNamespaceId(key.namespaceId());
        if (namespace == null || !Boolean.TRUE.equals(namespace.getIsActive())) {
            throw new IllegalArgumentException("Namespace is missing or inactive: " + key.namespaceId());
        }
        if (groupRepository.find(key.namespaceId(), key.groupName()) == null) {
            throw new IllegalArgumentException("Group does not exist: " + key.groupName());
        }

        String contentType = draft.getContentType() == null ? "text" : draft.getContentType().trim().toLowerCase();
        if (!CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported contentType: " + draft.getContentType());
        }

        draft.setNamespaceId(key.namespaceId());
        draft.setGroupName(key.groupName());
        draft.setDataId(key.dataId());
        draft.setContentType(contentType);
        draft.setChecksum(checksum(draft.getContent()));
        draft.setIsEncrypted(Boolean.TRUE.equals(draft.getIsEncrypted()));
        draft.setUpdatedBy(operator);
        draft.setUpdatedAt(new Date());

        ConfigResource existing = resourceRepository.find(key);
        if (existing == null) {
            draft.setRevision(0L);
            draft.setCreatedBy(operator);
            draft.setCreatedAt(new Date());
            resourceRepository.save(draft);
        } else {
            draft.setId(existing.getId());
            draft.setRevision(existing.getRevision());
            draft.setCreatedBy(existing.getCreatedBy());
            draft.setCreatedAt(existing.getCreatedAt());
            resourceRepository.updateDraft(draft);
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

    public List<AuditLog> findAudits(String namespaceId, String groupName, String dataId) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        return auditLogRepository.findByResource(
                key.namespaceId(), key.groupName(), "CONFIG", key.dataId());
    }

    @Transaction
    public ConfigRelease publish(
            String namespaceId, String groupName, String dataId, ReleaseType releaseType, String operator) {
        ConfigResourceKey key = ConfigResourceKey.of(namespaceId, groupName, dataId);
        ConfigResource resource = resourceRepository.find(key);
        if (resource == null) {
            throw new IllegalArgumentException("Config resource does not exist: " + key.canonicalName());
        }

        ConfigRelease release = createRelease(resource, key, releaseType == null ? ReleaseType.FULL : releaseType,
                operator, "CONFIG_PUBLISHED", false);
        publishTotal.incrementAndGet();
        return release;
    }

    @Transaction
    public List<ConfigRelease> publishBatch(
            String namespaceId,
            String groupName,
            List<String> dataIds,
            ReleaseType releaseType,
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
            keys.add(key);
            resources.add(resource);
        }

        ReleaseType normalizedType = releaseType == null ? ReleaseType.FULL : releaseType;
        List<ConfigRelease> releases = new ArrayList<>(resources.size());
        for (int i = 0; i < resources.size(); i++) {
            releases.add(createRelease(resources.get(i), keys.get(i), normalizedType,
                    operator, "CONFIG_BATCH_PUBLISHED", true));
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
        ConfigRelease target = releaseRepository.findByReleaseId(releaseId);
        if (target == null || !resource.getId().equals(target.getConfigId())) {
            throw new IllegalArgumentException("Release does not belong to config resource: " + releaseId);
        }

        resource.setContent(target.getContent());
        resource.setContentType(target.getContentType());
        resource.setChecksum(target.getChecksum());
        resource.setUpdatedBy(operator);
        resource.setUpdatedAt(new Date());
        if (resourceRepository.updateDraft(resource) != 1) {
            throw new IllegalStateException("Failed to restore release content: " + releaseId);
        }
        ConfigRelease release = createRelease(resource, key, ReleaseType.ROLLBACK, operator,
                "CONFIG_ROLLED_BACK", false);
        rollbackTotal.incrementAndGet();
        return release;
    }

    public long publishTotal() { return publishTotal.get(); }
    public long rollbackTotal() { return rollbackTotal.get(); }
    public long batchTotal() { return batchTotal.get(); }
    public long batchReleaseTotal() { return batchReleaseTotal.get(); }

    private ConfigRelease createRelease(
            ConfigResource resource,
            ConfigResourceKey key,
            ReleaseType releaseType,
            String operator,
            String operation,
            boolean batch) {

        long currentRevision = resource.getRevision() == null ? 0L : resource.getRevision();
        long nextRevision = currentRevision + 1;
        if (resourceRepository.updateRevision(resource.getId(), currentRevision, nextRevision) != 1) {
            throw new IllegalStateException("Config resource was modified concurrently: " + key.canonicalName());
        }

        ConfigRelease release = new ConfigRelease();
        release.setReleaseId(UUID.randomUUID().toString());
        release.setConfigId(resource.getId());
        release.setNamespaceId(key.namespaceId());
        release.setGroupName(key.groupName());
        release.setDataId(key.dataId());
        release.setRevision(nextRevision);
        release.setContent(resource.getContent());
        release.setContentType(resource.getContentType());
        release.setChecksum(resource.getChecksum());
        release.setReleaseType(releaseType.name());
        release.setOperator(operator);
        release.setReleasedAt(new Date());
        if (releaseRepository.save(release) != 1) {
            throw new IllegalStateException("Failed to save config release: " + key.canonicalName());
        }

        AuditLog auditLog = new AuditLog();
        auditLog.setNamespaceId(key.namespaceId());
        auditLog.setGroupName(key.groupName());
        auditLog.setResourceType("CONFIG");
        auditLog.setResourceName(key.dataId());
        auditLog.setOperation(operation);
        auditLog.setOperator(operator);
        auditLog.setDetail(ONode.serialize(Map.of(
                "releaseId", release.getReleaseId(),
                "revision", release.getRevision(),
                "releaseType", release.getReleaseType(),
                "checksum", release.getChecksum(),
                "batch", batch)));
        auditLog.setCreatedAt(new Date());
        if (auditLogRepository.save(auditLog) != 1) {
            throw new IllegalStateException("Failed to save release audit: " + key.canonicalName());
        }
        return release;
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
}
