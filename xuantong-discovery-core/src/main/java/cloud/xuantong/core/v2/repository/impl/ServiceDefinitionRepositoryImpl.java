package cloud.xuantong.core.v2.repository.impl;

import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceKey;
import cloud.xuantong.core.v2.repository.ServiceDefinitionRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;

@Component
public class ServiceDefinitionRepositoryImpl implements ServiceDefinitionRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public ServiceDefinition find(ServiceKey key) {
        return easyQuery.queryable(ServiceDefinition.class)
                .where(o -> {
                    o.namespaceId().eq(key.namespaceId());
                    o.groupName().eq(key.groupName());
                    o.serviceName().eq(key.serviceName());
                })
                .firstOrNull();
    }

    @Override
    public List<ServiceDefinition> findByGroup(String namespaceId, String groupName) {
        return easyQuery.queryable(ServiceDefinition.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                })
                .orderBy(o -> o.serviceName().asc())
                .toList();
    }

    @Override
    public long save(ServiceDefinition service) {
        return easyQuery.insertable(service).executeRows(true);
    }

    @Override
    public long update(ServiceDefinition service) {
        return easyQuery.updatable(ServiceDefinition.class)
                .setColumns(o -> o.description().set(service.getDescription()))
                .setColumns(o -> o.metadata().set(service.getMetadata()))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> o.id().eq(service.getId()))
                .executeRows();
    }

    @Override
    public long delete(ServiceKey key) {
        return easyQuery.deletable(ServiceDefinition.class)
                .allowDeleteStatement(true)
                .where(o -> {
                    o.namespaceId().eq(key.namespaceId());
                    o.groupName().eq(key.groupName());
                    o.serviceName().eq(key.serviceName());
                })
                .executeRows();
    }
}
