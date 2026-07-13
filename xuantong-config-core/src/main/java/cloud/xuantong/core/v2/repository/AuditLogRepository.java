package cloud.xuantong.core.v2.repository;

import cloud.xuantong.core.v2.model.AuditLog;

import java.util.List;

public interface AuditLogRepository {
    long save(AuditLog auditLog);

    List<AuditLog> findByResource(
            String namespaceId, String groupName, String resourceType, String resourceName);

    List<AuditLog> findRecent(int limit);
}
