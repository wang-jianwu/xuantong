package cloud.xuantong.config.management.repository.impl;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
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
    public ConfigRelease findByOperationId(String operationId) {
        return easyQuery.queryable(ConfigRelease.class)
                .where(o -> o.operationId().eq(operationId))
                .firstOrNull();
    }

    @Override
    public ConfigRelease findLatestStable(Long configId) {
        return easyQuery.queryable(ConfigRelease.class)
                .where(o -> {
                    o.configId().eq(configId);
                    o.releaseType().ne("GRAY_IP");
                    o.releaseType().ne("GRAY_PERCENTAGE");
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
