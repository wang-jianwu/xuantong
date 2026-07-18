package cloud.xuantong.discovery.management.repository.impl;

import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.resource.model.ServiceKey;
import cloud.xuantong.discovery.management.repository.ServiceDefinitionRepository;
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
    public List<ServiceDefinition> findByLifecycleState(
            String lifecycleState, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1_000));
        return easyQuery.queryable(ServiceDefinition.class)
                .where(o -> o.lifecycleState().eq(lifecycleState))
                .orderBy(o -> o.updatedAt().asc())
                .limit(safeLimit)
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
    public long updateLifecycle(ServiceDefinition service) {
        return easyQuery.updatable(ServiceDefinition.class)
                .setColumns(o -> o.serviceGeneration().set(service.getServiceGeneration()))
                .setColumns(o -> o.lifecycleState().set(service.getLifecycleState()))
                .setColumns(o -> o.lifecycleOperationId().set(
                        service.getLifecycleOperationId()))
                .setColumns(o -> o.lifecycleError().set(service.getLifecycleError()))
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
