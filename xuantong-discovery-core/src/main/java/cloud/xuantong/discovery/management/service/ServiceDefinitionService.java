package cloud.xuantong.discovery.management.service;

import cloud.xuantong.resource.model.ConfigNamespace;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.resource.model.ServiceKey;
import cloud.xuantong.resource.model.ResourceNameRules;
import cloud.xuantong.resource.repository.NamespaceRepository;
import cloud.xuantong.resource.repository.ResourceGroupRepository;
import cloud.xuantong.discovery.management.repository.ServiceDefinitionRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.Date;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class ServiceDefinitionService {
    public static final String LIFECYCLE_ACTIVATING = "ACTIVATING";
    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_DELETING = "DELETING";

    @Inject
    private ServiceDefinitionRepository serviceRepository;
    @Inject
    private NamespaceRepository namespaceRepository;
    @Inject
    private ResourceGroupRepository groupRepository;

    public List<ServiceDefinition> findByGroup(String namespaceId, String groupName) {
        return serviceRepository.findByGroup(
                ResourceNameRules.validate("namespaceId", namespaceId),
                ResourceNameRules.validate("groupName", groupName));
    }

    public ServiceDefinition find(String namespaceId, String groupName, String serviceName) {
        return serviceRepository.find(ServiceKey.of(namespaceId, groupName, serviceName));
    }

    public List<ServiceDefinition> findPendingLifecycle(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1_000));
        return java.util.stream.Stream.concat(
                        serviceRepository.findByLifecycleState(
                                LIFECYCLE_ACTIVATING, safeLimit).stream(),
                        serviceRepository.findByLifecycleState(
                                LIFECYCLE_DELETING, safeLimit).stream())
                .sorted(Comparator.comparing(
                        ServiceDefinition::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(safeLimit)
                .toList();
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
        ServiceDefinition existing = serviceRepository.find(key);
        if (existing != null && LIFECYCLE_ACTIVATING.equals(existing.getLifecycleState())) {
            return existing;
        }
        if (existing != null) {
            throw new IllegalArgumentException("Service already exists: " + key.canonicalName());
        }

        service.setNamespaceId(key.namespaceId());
        service.setGroupName(key.groupName());
        service.setServiceName(key.serviceName());
        service.setServiceGeneration(0L);
        service.setLifecycleState(LIFECYCLE_ACTIVATING);
        service.setLifecycleOperationId(UUID.randomUUID().toString());
        service.setLifecycleError(null);
        service.setCreatedBy(operator);
        service.setCreatedAt(new Date());
        service.setUpdatedAt(new Date());
        serviceRepository.save(service);
        return service;
    }

    public ServiceDefinition markActive(ServiceDefinition service, long generation) {
        if (service == null || generation < 0) {
            throw new IllegalArgumentException("Service and generation are required");
        }
        service.setServiceGeneration(generation);
        service.setLifecycleState(LIFECYCLE_ACTIVE);
        service.setLifecycleOperationId(null);
        service.setLifecycleError(null);
        service.setUpdatedAt(new Date());
        requireLifecycleUpdate(service);
        return service;
    }

    public ServiceDefinition markDeleting(ServiceDefinition service) {
        if (service == null) {
            throw new IllegalArgumentException("Service is required");
        }
        if (!LIFECYCLE_DELETING.equals(service.getLifecycleState())
                || service.getLifecycleOperationId() == null
                || service.getLifecycleOperationId().isBlank()) {
            service.setLifecycleOperationId(UUID.randomUUID().toString());
        }
        service.setLifecycleState(LIFECYCLE_DELETING);
        service.setLifecycleError(null);
        service.setUpdatedAt(new Date());
        requireLifecycleUpdate(service);
        return service;
    }

    public ServiceDefinition recordLifecycleFailure(
            ServiceDefinition service, String message) {
        if (service == null) {
            throw new IllegalArgumentException("Service is required");
        }
        String normalized = message == null || message.isBlank()
                ? "Service lifecycle operation failed" : message.trim();
        String bounded = normalized.length() <= 2_000
                ? normalized : normalized.substring(0, 2_000);
        if (bounded.equals(service.getLifecycleError())) {
            return service;
        }
        service.setLifecycleError(bounded);
        service.setUpdatedAt(new Date());
        requireLifecycleUpdate(service);
        return service;
    }

    public ServiceDefinition update(
            String namespaceId, String groupName, String serviceName, ServiceDefinition changes) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        ServiceDefinition existing = serviceRepository.find(key);
        if (existing == null) {
            throw new IllegalArgumentException("Service does not exist: " + key.canonicalName());
        }
        if (!LIFECYCLE_ACTIVE.equals(existing.getLifecycleState())) {
            throw new IllegalStateException(
                    "Service lifecycle is not ACTIVE: " + existing.getLifecycleState());
        }
        existing.setDescription(changes.getDescription());
        existing.setMetadata(changes.getMetadata());
        existing.setUpdatedAt(new Date());
        serviceRepository.update(existing);
        return existing;
    }

    public boolean delete(String namespaceId, String groupName, String serviceName) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        return serviceRepository.delete(key) > 0;
    }

    private void requireLifecycleUpdate(ServiceDefinition service) {
        if (service.getId() == null || serviceRepository.updateLifecycle(service) != 1) {
            throw new IllegalStateException(
                    "Service lifecycle projection could not be updated: "
                            + service.getServiceName());
        }
    }
}
