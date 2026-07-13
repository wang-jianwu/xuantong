package cloud.xuantong.core.v2.repository;

import cloud.xuantong.core.v2.model.ConfigRelease;

import java.util.List;

public interface ConfigReleaseRepository {
    long save(ConfigRelease release);
    ConfigRelease findByReleaseId(String releaseId);
    ConfigRelease findLatest(String namespaceId, String groupName, String dataId);
    List<ConfigRelease> findByConfigId(Long configId);
}
