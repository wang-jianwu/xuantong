package cloud.xuantong.config.management.repository;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.RolloutStatus;

import java.util.List;

public interface ConfigRolloutRepository {
    long save(ConfigRollout rollout);
    ConfigRollout findActive(Long configId);
    ConfigRollout findByRolloutId(String rolloutId);
    List<ConfigRollout> findByConfigId(Long configId);
    PageResult<ConfigRollout> findPageByConfigId(Long configId, PageQuery pageQuery);
    long complete(String rolloutId, RolloutStatus expectedStatus, RolloutStatus newStatus,
                  String operator);
    default long completeProjection(
            String rolloutId,
            RolloutStatus expectedStatus,
            RolloutStatus newStatus,
            String operator,
            String operationId,
            long decisionRevision) {
        return complete(rolloutId, expectedStatus, newStatus, operator);
    }
}
