package cloud.xuantong.config.management.repository.impl;

import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.RolloutStatus;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;

@Component
public class ConfigRolloutRepositoryImpl implements ConfigRolloutRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public long save(ConfigRollout rollout) {
        return easyQuery.insertable(rollout).executeRows(true);
    }

    @Override
    public ConfigRollout findActive(Long configId) {
        return easyQuery.queryable(ConfigRollout.class)
                .where(o -> {
                    o.configId().eq(configId);
                    o.status().eq(RolloutStatus.ACTIVE.name());
                })
                .orderBy(o -> o.createdAt().desc())
                .firstOrNull();
    }

    @Override
    public ConfigRollout findByRolloutId(String rolloutId) {
        return easyQuery.queryable(ConfigRollout.class)
                .where(o -> o.rolloutId().eq(rolloutId))
                .firstOrNull();
    }

    @Override
    public List<ConfigRollout> findByConfigId(Long configId) {
        return easyQuery.queryable(ConfigRollout.class)
                .where(o -> o.configId().eq(configId))
                .orderBy(o -> o.createdAt().desc())
                .toList();
    }

    @Override
    public long complete(String rolloutId, RolloutStatus expectedStatus,
                         RolloutStatus newStatus, String operator) {
        return easyQuery.updatable(ConfigRollout.class)
                .setColumns(o -> o.status().set(newStatus.name()))
                .setColumns(o -> o.completedBy().set(operator))
                .setColumns(o -> o.completedAt().set(new Date()))
                .where(o -> {
                    o.rolloutId().eq(rolloutId);
                    o.status().eq(expectedStatus.name());
                })
                .executeRows();
    }

    @Override
    public long completeProjection(
            String rolloutId,
            RolloutStatus expectedStatus,
            RolloutStatus newStatus,
            String operator,
            String operationId,
            long decisionRevision) {
        return easyQuery.updatable(ConfigRollout.class)
                .setColumns(o -> o.status().set(newStatus.name()))
                .setColumns(o -> o.completedBy().set(operator))
                .setColumns(o -> o.completedAt().set(new Date()))
                .setColumns(o -> o.completeOperationId().set(operationId))
                .setColumns(o -> o.decisionRevision().set(decisionRevision))
                .where(o -> {
                    o.rolloutId().eq(rolloutId);
                    o.status().eq(expectedStatus.name());
                })
                .executeRows();
    }
}
