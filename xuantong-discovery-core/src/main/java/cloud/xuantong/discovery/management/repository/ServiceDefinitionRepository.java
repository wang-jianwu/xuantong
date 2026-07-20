package cloud.xuantong.discovery.management.repository;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.resource.model.ServiceKey;

import java.util.List;

public interface ServiceDefinitionRepository {
    ServiceDefinition find(ServiceKey key);
    default List<ServiceDefinition> findAll() {
        return List.of();
    }
    List<ServiceDefinition> findByGroup(String namespaceId, String groupName);
    PageResult<ServiceDefinition> findPageByGroup(
            String namespaceId, String groupName, String keyword,
            String lifecycleState, PageQuery pageQuery);
    List<ServiceDefinition> findByLifecycleState(String lifecycleState, int limit);
    long save(ServiceDefinition service);
    long update(ServiceDefinition service);
    long updateLifecycle(ServiceDefinition service);
    long delete(ServiceKey key);
}
