package cloud.xuantong.core.v2.repository;

import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceKey;

import java.util.List;

public interface ServiceDefinitionRepository {
    ServiceDefinition find(ServiceKey key);
    List<ServiceDefinition> findByGroup(String namespaceId, String groupName);
    long save(ServiceDefinition service);
    long update(ServiceDefinition service);
    long delete(ServiceKey key);
}
