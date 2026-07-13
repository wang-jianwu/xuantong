package cloud.xuantong.core.v2.repository.impl;

import cloud.xuantong.core.v2.model.ResourceGroup;
import cloud.xuantong.core.v2.repository.ResourceGroupRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.List;

@Component
public class ResourceGroupRepositoryImpl implements ResourceGroupRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public List<ResourceGroup> findByNamespace(String namespaceId) {
        return easyQuery.queryable(ResourceGroup.class)
                .where(o -> o.namespaceId().eq(namespaceId))
                .orderBy(o -> o.groupName().asc())
                .toList();
    }

    @Override
    public ResourceGroup find(String namespaceId, String groupName) {
        return easyQuery.queryable(ResourceGroup.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                })
                .firstOrNull();
    }

    @Override
    public long save(ResourceGroup group) {
        return easyQuery.insertable(group).executeRows(true);
    }
}
