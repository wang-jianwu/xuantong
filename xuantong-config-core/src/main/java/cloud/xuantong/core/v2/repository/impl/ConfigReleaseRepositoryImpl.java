package cloud.xuantong.core.v2.repository.impl;

import cloud.xuantong.core.v2.model.ConfigRelease;
import cloud.xuantong.core.v2.repository.ConfigReleaseRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.List;

@Component
public class ConfigReleaseRepositoryImpl implements ConfigReleaseRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public long save(ConfigRelease release) {
        return easyQuery.insertable(release).executeRows(true);
    }

    @Override
    public ConfigRelease findByReleaseId(String releaseId) {
        return easyQuery.queryable(ConfigRelease.class)
                .where(o -> o.releaseId().eq(releaseId))
                .firstOrNull();
    }

    @Override
    public ConfigRelease findLatest(String namespaceId, String groupName, String dataId) {
        return easyQuery.queryable(ConfigRelease.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                    o.dataId().eq(dataId);
                })
                .orderBy(o -> o.revision().desc())
                .firstOrNull();
    }

    @Override
    public List<ConfigRelease> findByConfigId(Long configId) {
        return easyQuery.queryable(ConfigRelease.class)
                .where(o -> o.configId().eq(configId))
                .orderBy(o -> o.revision().desc())
                .toList();
    }
}
