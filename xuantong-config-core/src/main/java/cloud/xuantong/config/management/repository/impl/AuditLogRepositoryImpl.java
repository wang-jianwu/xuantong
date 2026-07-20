package cloud.xuantong.config.management.repository.impl;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.repository.AuditLogFilter;
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
        return easyQuery.queryable(AuditLog.class)
                .orderBy(o -> o.id().desc())
                .limit(safeLimit)
                .toList();
    }

    @Override
    public PageResult<AuditLog> findPage(AuditLogFilter filter, PageQuery pageQuery) {
        AuditLogFilter safeFilter = filter == null
                ? AuditLogFilter.recent(null, null, null)
                : filter;
        String namespaceId = normalize(safeFilter.namespaceId());
        String groupName = normalize(safeFilter.groupName());
        String resourceType = normalize(safeFilter.resourceType());
        String resourceName = normalize(safeFilter.resourceName());
        String operation = normalize(safeFilter.operation());
        String operator = normalize(safeFilter.operator());
        String keyword = normalize(safeFilter.keyword());
        var result = easyQuery.queryable(AuditLog.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId != null, namespaceId);
                    o.groupName().eq(groupName != null, groupName);
                    o.resourceType().eq(resourceType != null, resourceType);
                    o.resourceName().eq(resourceName != null, resourceName);
                    o.operation().contains(operation != null, operation);
                    o.operator().eq(operator != null, operator);
                    if (keyword != null) {
                        o.or(() -> {
                            o.resourceName().contains(keyword);
                            o.operator().contains(keyword);
                            o.operation().contains(keyword);
                        });
                    }
                })
                .orderBy(o -> o.id().desc())
                .toPageResult(pageQuery.page(), pageQuery.pageSize());
        return PageResult.of(pageQuery, result.getTotal(), result.getData());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
