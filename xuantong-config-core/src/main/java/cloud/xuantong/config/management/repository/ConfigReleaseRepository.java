package cloud.xuantong.config.management.repository;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.model.ConfigRelease;

import java.util.List;

public interface ConfigReleaseRepository {
    long save(ConfigRelease release);
    ConfigRelease findByReleaseId(String releaseId);
    default ConfigRelease findByOperationId(String operationId) {
        return null;
    }
    default ConfigRelease findByDecisionRevision(Long configId, long decisionRevision) {
        return null;
    }
    default List<ConfigRelease> findByContentRevision(
            Long configId, long contentRevision) {
        return List.of();
    }
    ConfigRelease findLatestStable(Long configId);
    List<ConfigRelease> findByConfigId(Long configId);
    PageResult<ConfigRelease> findPageByConfigId(Long configId, PageQuery pageQuery);
}
