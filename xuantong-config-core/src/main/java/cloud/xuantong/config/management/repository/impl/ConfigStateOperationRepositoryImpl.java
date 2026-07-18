package cloud.xuantong.config.management.repository.impl;

import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.model.ConfigStateOperationStatus;
import cloud.xuantong.config.management.repository.ConfigStateOperationRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;

@Component
public class ConfigStateOperationRepositoryImpl implements ConfigStateOperationRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public long save(ConfigStateOperation operation) {
        return easyQuery.insertable(operation).executeRows(true);
    }

    @Override
    public ConfigStateOperation find(String tenant, String principal, String operationId) {
        return easyQuery.queryable(ConfigStateOperation.class)
                .where(o -> {
                    o.tenant().eq(tenant);
                    o.principal().eq(principal);
                    o.operationId().eq(operationId);
                })
                .firstOrNull();
    }

    @Override
    public ConfigStateOperation findUnfinishedForConfig(
            String namespaceId, String groupName, String dataId) {
        return easyQuery.queryable(ConfigStateOperation.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                    o.dataId().eq(dataId);
                    o.status().ne(ConfigStateOperationStatus.PROJECTED.name());
                    o.status().ne(ConfigStateOperationStatus.FAILED.name());
                })
                .orderBy(o -> o.createdAt().asc())
                .firstOrNull();
    }

    @Override
    public ConfigStateOperation findAnyNonFailedForConfig(
            String namespaceId, String groupName, String dataId) {
        return easyQuery.queryable(ConfigStateOperation.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                    o.dataId().eq(dataId);
                    o.status().ne(ConfigStateOperationStatus.FAILED.name());
                })
                .orderBy(o -> o.createdAt().asc())
                .firstOrNull();
    }

    @Override
    public List<ConfigStateOperation> findRecoverable(int limit) {
        int safeLimit = Math.clamp(limit, 1, 1_000);
        List<ConfigStateOperation> operations = easyQuery
                .queryable(ConfigStateOperation.class)
                .where(o -> {
                    o.status().ne(ConfigStateOperationStatus.PROJECTED.name());
                    o.status().ne(ConfigStateOperationStatus.FAILED.name());
                })
                .orderBy(o -> o.createdAt().asc())
                .toList();
        return operations.size() <= safeLimit
                ? operations
                : operations.subList(0, safeLimit);
    }

    @Override
    public long markCommitted(
            Long id, long contentRevision, long decisionRevision, long eventRevision) {
        return easyQuery.updatable(ConfigStateOperation.class)
                .setColumns(o -> o.status().set(ConfigStateOperationStatus.COMMITTED.name()))
                .setColumns(o -> o.contentRevision().set(contentRevision))
                .setColumns(o -> o.decisionRevision().set(decisionRevision))
                .setColumns(o -> o.eventRevision().set(eventRevision))
                .setColumns(o -> o.errorMessage().set((String) null))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> {
                    o.id().eq(id);
                    o.status().eq(ConfigStateOperationStatus.PENDING.name());
                })
                .executeRows();
    }

    @Override
    public long markProjectionPending(Long id, String errorMessage) {
        return updateError(
                id, ConfigStateOperationStatus.PROJECTION_PENDING, errorMessage, false);
    }

    @Override
    public long markProjected(Long id) {
        return easyQuery.updatable(ConfigStateOperation.class)
                .setColumns(o -> o.status().set(ConfigStateOperationStatus.PROJECTED.name()))
                .setColumns(o -> o.errorMessage().set((String) null))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> o.id().eq(id))
                .executeRows();
    }

    @Override
    public long markFailed(Long id, String errorMessage) {
        return updateError(id, ConfigStateOperationStatus.FAILED, errorMessage, true);
    }

    @Override
    public long updatePendingError(Long id, String errorMessage) {
        return updateError(id, ConfigStateOperationStatus.PENDING, errorMessage, true);
    }

    @Override
    public long updateStatus(
            Long id,
            ConfigStateOperationStatus expected,
            ConfigStateOperationStatus status) {
        return easyQuery.updatable(ConfigStateOperation.class)
                .setColumns(o -> o.status().set(status.name()))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> {
                    o.id().eq(id);
                    o.status().eq(expected.name());
                })
                .executeRows();
    }

    private long updateError(
            Long id,
            ConfigStateOperationStatus status,
            String errorMessage,
            boolean requirePending) {
        var update = easyQuery.updatable(ConfigStateOperation.class)
                .setColumns(o -> o.status().set(status.name()))
                .setColumns(o -> o.errorMessage().set(truncate(errorMessage)))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> {
                    o.id().eq(id);
                    if (requirePending) {
                        o.status().eq(ConfigStateOperationStatus.PENDING.name());
                    } else {
                        o.status().ne(ConfigStateOperationStatus.PROJECTED.name());
                    }
                });
        return update.executeRows();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
