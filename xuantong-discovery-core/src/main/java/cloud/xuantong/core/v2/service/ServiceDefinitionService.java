package cloud.xuantong.core.v2.service;

import cloud.xuantong.core.v2.model.ConfigNamespace;
import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceKey;
import cloud.xuantong.core.v2.model.ResourceNameRules;
import cloud.xuantong.core.v2.repository.NamespaceRepository;
import cloud.xuantong.core.v2.repository.ResourceGroupRepository;
import cloud.xuantong.core.v2.repository.ServiceDefinitionRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.Date;
import java.util.List;

@Component
public class ServiceDefinitionService {
    @Inject
    private ServiceDefinitionRepository serviceRepository;
    @Inject
    private NamespaceRepository namespaceRepository;
    @Inject
    private ResourceGroupRepository groupRepository;
    @Inject
    private ServiceInstanceRegistry instanceRegistry;

    public List<ServiceDefinition> findByGroup(String namespaceId, String groupName) {
        return serviceRepository.findByGroup(
                ResourceNameRules.validate("namespaceId", namespaceId),
                ResourceNameRules.validate("groupName", groupName));
    }

    public ServiceDefinition find(String namespaceId, String groupName, String serviceName) {
        return serviceRepository.find(ServiceKey.of(namespaceId, groupName, serviceName));
    }

    public ServiceDefinition create(ServiceDefinition service, String operator) {
        ServiceKey key = ServiceKey.of(
                service.getNamespaceId(), service.getGroupName(), service.getServiceName());
        ConfigNamespace namespace = namespaceRepository.findByNamespaceId(key.namespaceId());
        if (namespace == null || !Boolean.TRUE.equals(namespace.getIsActive())) {
            throw new IllegalArgumentException("Namespace is missing or inactive: " + key.namespaceId());
        }
        if (groupRepository.find(key.namespaceId(), key.groupName()) == null) {
            throw new IllegalArgumentException("Group does not exist: " + key.groupName());
        }
        if (serviceRepository.find(key) != null) {
            throw new IllegalArgumentException("Service already exists: " + key.canonicalName());
        }

        service.setNamespaceId(key.namespaceId());
        service.setGroupName(key.groupName());
        service.setServiceName(key.serviceName());
        service.setCreatedBy(operator);
        service.setCreatedAt(new Date());
        service.setUpdatedAt(new Date());
        serviceRepository.save(service);
        return service;
    }

    public ServiceDefinition update(
            String namespaceId, String groupName, String serviceName, ServiceDefinition changes) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        ServiceDefinition existing = serviceRepository.find(key);
        if (existing == null) {
            throw new IllegalArgumentException("Service does not exist: " + key.canonicalName());
        }
        existing.setDescription(changes.getDescription());
        existing.setMetadata(changes.getMetadata());
        existing.setUpdatedAt(new Date());
        serviceRepository.update(existing);
        return existing;
    }

    public boolean delete(String namespaceId, String groupName, String serviceName) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        if (instanceRegistry.hasInstances(key)) {
            throw new IllegalStateException("Service still has registered instances: " + key.canonicalName());
        }
        boolean deleted = serviceRepository.delete(key) > 0;
        if (deleted) {
            instanceRegistry.removeService(key);
        }
        return deleted;
    }
}
