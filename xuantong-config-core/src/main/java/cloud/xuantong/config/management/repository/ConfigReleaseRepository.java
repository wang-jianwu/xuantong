package cloud.xuantong.config.management.repository;

import cloud.xuantong.config.management.model.ConfigRelease;

import java.util.List;

public interface ConfigReleaseRepository {
    long save(ConfigRelease release);
    ConfigRelease findByReleaseId(String releaseId);
    default ConfigRelease findByOperationId(String operationId) {
        return null;
    }
    ConfigRelease findLatestStable(Long configId);
    List<ConfigRelease> findByConfigId(Long configId);
}
