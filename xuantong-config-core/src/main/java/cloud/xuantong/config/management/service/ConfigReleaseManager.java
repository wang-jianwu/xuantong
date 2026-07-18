package cloud.xuantong.config.management.service;

import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.config.management.repository.AuditLogRepository;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Creates immutable releases and their audit records under one optimistic revision fence. */
@Component
public class ConfigReleaseManager {
    @Inject
    private ConfigResourceRepository resourceRepository;
    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private AuditLogRepository auditLogRepository;

    public ConfigRelease create(
            ConfigResource resource,
            ConfigResourceKey key,
            ReleaseType releaseType,
            String operator,
            String operation,
            boolean batch,
            String content,
            String contentType,
            String checksum,
            Map<String, Object> extraAuditDetail) {
        long currentRevision = resource.getRevision() == null ? 0L : resource.getRevision();
        long nextRevision = currentRevision + 1;
        if (resourceRepository.updateRevision(
                resource.getId(), currentRevision, resource.getChecksum(), nextRevision) != 1) {
            throw new IllegalStateException("Config resource was modified concurrently: " + key.canonicalName());
        }

        ConfigRelease release = new ConfigRelease();
        release.setReleaseId(UUID.randomUUID().toString());
        release.setConfigId(resource.getId());
        release.setNamespaceId(key.namespaceId());
        release.setGroupName(key.groupName());
        release.setDataId(key.dataId());
        release.setRevision(nextRevision);
        release.setContent(content);
        release.setContentType(contentType);
        release.setChecksum(checksum);
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
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("releaseId", release.getReleaseId());
        detail.put("revision", release.getRevision());
        detail.put("releaseType", release.getReleaseType());
        detail.put("checksum", release.getChecksum());
        detail.put("batch", batch);
        if (extraAuditDetail != null) detail.putAll(extraAuditDetail);
        auditLog.setDetail(ONode.serialize(detail));
        auditLog.setCreatedAt(new Date());
        if (auditLogRepository.save(auditLog) != 1) {
            throw new IllegalStateException("Failed to save release audit: " + key.canonicalName());
        }
        return release;
    }
}
