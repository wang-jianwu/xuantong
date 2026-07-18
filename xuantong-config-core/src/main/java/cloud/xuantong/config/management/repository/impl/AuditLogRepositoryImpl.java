package cloud.xuantong.config.management.repository.impl;

import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.repository.AuditLogRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.List;

@Component
public class AuditLogRepositoryImpl implements AuditLogRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public long save(AuditLog auditLog) {
        return easyQuery.insertable(auditLog).executeRows(true);
    }

    @Override
    public AuditLog findByOperationId(String operationId) {
        return easyQuery.queryable(AuditLog.class)
                .where(o -> o.operationId().eq(operationId))
                .firstOrNull();
    }

    @Override
    public List<AuditLog> findByResource(
            String namespaceId, String groupName, String resourceType, String resourceName) {
        return easyQuery.queryable(AuditLog.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                    o.resourceType().eq(resourceType);
                    o.resourceName().eq(resourceName);
                })
                .orderBy(o -> o.id().desc())
                .toList();
    }

    @Override
    public List<AuditLog> findRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AuditLog> logs = easyQuery.queryable(AuditLog.class)
                .orderBy(o -> o.id().desc())
                .toList();
        return logs.size() <= safeLimit ? logs : logs.subList(0, safeLimit);
    }
}
