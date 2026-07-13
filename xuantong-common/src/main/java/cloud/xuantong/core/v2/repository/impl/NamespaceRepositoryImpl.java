package cloud.xuantong.core.v2.repository.impl;

import cloud.xuantong.core.v2.model.ConfigNamespace;
import cloud.xuantong.core.v2.repository.NamespaceRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;

@Component
public class NamespaceRepositoryImpl implements NamespaceRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public List<ConfigNamespace> findAll() {
        return easyQuery.queryable(ConfigNamespace.class)
                .orderBy(o -> o.namespaceId().asc())
                .toList();
    }

    @Override
    public ConfigNamespace findByNamespaceId(String namespaceId) {
        return easyQuery.queryable(ConfigNamespace.class)
                .where(o -> o.namespaceId().eq(namespaceId))
                .firstOrNull();
    }

    @Override
    public long save(ConfigNamespace namespace) {
        return easyQuery.insertable(namespace).executeRows(true);
    }

    @Override
    public long update(ConfigNamespace namespace) {
        return easyQuery.updatable(ConfigNamespace.class)
                .setColumns(o -> o.name().set(namespace.getName()))
                .setColumns(o -> o.description().set(namespace.getDescription()))
                .setColumns(o -> o.labels().set(namespace.getLabels()))
                .setColumns(o -> o.isActive().set(namespace.getIsActive()))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> o.namespaceId().eq(namespace.getNamespaceId()))
                .executeRows();
    }
}
