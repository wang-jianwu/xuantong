package cloud.xuantong.server.state.management;

import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.service.ServiceDefinitionService;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

@Component
@Slf4j
public final class ServiceDefinitionLifecycleCoordinator {
    @Inject
    private ServiceDefinitionService definitions;
    @Inject
    private RegistryStateManagementService registry;

    public ServiceDefinition create(ServiceDefinition requested, String operator) {
        ServiceDefinition pending = definitions.create(requested, operator);
        return activatePending(pending);
    }

    public boolean delete(String namespace, String group, String serviceName) {
        ServiceDefinition service = definitions.find(namespace, group, serviceName);
        if (service == null) {
            return false;
        }
        definitions.markDeleting(service);
        return deletePending(service);
    }

    public void recoverPending() {
        for (ServiceDefinition service : definitions.findPendingLifecycle(100)) {
            try {
                if (ServiceDefinitionService.LIFECYCLE_ACTIVATING.equals(
                        service.getLifecycleState())) {
                    activatePending(service);
                } else if (ServiceDefinitionService.LIFECYCLE_DELETING.equals(
                        service.getLifecycleState())) {
                    deletePending(service);
                }
            } catch (RuntimeException e) {
                log.debug(
                        "Service lifecycle projection remains pending: service={}:{}:{}, state={}",
                        service.getNamespaceId(),
                        service.getGroupName(),
                        service.getServiceName(),
                        service.getLifecycleState(),
                        e);
            }
        }
    }

    private ServiceDefinition activatePending(ServiceDefinition pending) {
        long generation;
        try {
            generation = registry.activateServiceDefinition(
                    pending.getNamespaceId(),
                    pending.getGroupName(),
                    pending.getServiceName(),
                    "service-activate:" + pending.getLifecycleOperationId());
        } catch (RuntimeException e) {
            definitions.recordLifecycleFailure(pending, safeMessage(e));
            throw e;
        }
        return definitions.markActive(pending, generation);
    }

    private boolean deletePending(ServiceDefinition service) {
        try {
            registry.deleteServiceDefinition(
                    service.getNamespaceId(),
                    service.getGroupName(),
                    service.getServiceName(),
                    service.getServiceGeneration() == null
                            ? 0L : service.getServiceGeneration(),
                    "service-delete:" + service.getLifecycleOperationId());
        } catch (RegistryLifecycleMutationException e) {
            definitions.markActive(service, e.serviceGeneration());
            definitions.recordLifecycleFailure(service, safeMessage(e));
            throw e;
        } catch (RuntimeException e) {
            definitions.recordLifecycleFailure(service, safeMessage(e));
            throw e;
        }
        boolean deleted = definitions.delete(
                service.getNamespaceId(),
                service.getGroupName(),
                service.getServiceName());
        return deleted || definitions.find(
                service.getNamespaceId(),
                service.getGroupName(),
                service.getServiceName()) == null;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
