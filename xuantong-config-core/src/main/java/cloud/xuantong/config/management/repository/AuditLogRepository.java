package cloud.xuantong.config.management.repository;

import cloud.xuantong.config.management.model.AuditLog;

import java.util.List;

public interface AuditLogRepository {
    long save(AuditLog auditLog);

    default AuditLog findByOperationId(String operationId) {
        return null;
    }

    List<AuditLog> findByResource(
            String namespaceId, String groupName, String resourceType, String resourceName);

    List<AuditLog> findRecent(int limit);
}
