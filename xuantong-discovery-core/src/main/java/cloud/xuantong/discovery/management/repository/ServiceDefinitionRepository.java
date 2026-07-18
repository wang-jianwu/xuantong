package cloud.xuantong.discovery.management.repository;

import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.resource.model.ServiceKey;

import java.util.List;

public interface ServiceDefinitionRepository {
    ServiceDefinition find(ServiceKey key);
    List<ServiceDefinition> findByGroup(String namespaceId, String groupName);
    List<ServiceDefinition> findByLifecycleState(String lifecycleState, int limit);
    long save(ServiceDefinition service);
    long update(ServiceDefinition service);
    long updateLifecycle(ServiceDefinition service);
    long delete(ServiceKey key);
}
