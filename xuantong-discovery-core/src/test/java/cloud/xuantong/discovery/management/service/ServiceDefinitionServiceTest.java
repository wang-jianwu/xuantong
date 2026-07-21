package cloud.xuantong.discovery.management.service;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.repository.ServiceDefinitionRepository;
import cloud.xuantong.resource.model.ServiceKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ServiceDefinitionServiceTest {
    @Test
    void createsMissingActiveProjection() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        ServiceDefinitionService service = service(repository);

        ServiceDefinition projected = service.ensureActiveProjection(
                "public", "DEFAULT_GROUP", "demo-app", 1L, "");

        assertEquals(1L, projected.getServiceGeneration());
        assertEquals(ServiceDefinitionService.LIFECYCLE_ACTIVE,
                projected.getLifecycleState());
        assertEquals("client-auto-register", projected.getCreatedBy());
        assertEquals(1, repository.saved);
        assertEquals(0, repository.updated);
    }

    @Test
    void repairsPendingOrStaleProjectionFromRegistryGeneration() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        ServiceDefinition existing = definition(
                7L, "demo-app", 1L, ServiceDefinitionService.LIFECYCLE_DELETING);
        existing.setLifecycleOperationId("delete-1");
        existing.setLifecycleError("pending");
        repository.values.put(key("demo-app"), existing);
        ServiceDefinitionService service = service(repository);

        ServiceDefinition projected = service.ensureActiveProjection(
                "public", "DEFAULT_GROUP", "demo-app", 2L, "ignored");

        assertSame(existing, projected);
        assertEquals(2L, projected.getServiceGeneration());
        assertEquals(ServiceDefinitionService.LIFECYCLE_ACTIVE,
                projected.getLifecycleState());
        assertNull(projected.getLifecycleOperationId());
        assertNull(projected.getLifecycleError());
        assertEquals(1, repository.updated);
    }

    @Test
    void concurrentInsertIsResolvedByRereadingProjection() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        repository.failNextSaveAsConcurrentInsert = true;
        ServiceDefinitionService service = service(repository);

        ServiceDefinition projected = service.ensureActiveProjection(
                "public", "DEFAULT_GROUP", "demo-app", 1L, "client-auto-register");

        assertEquals(ServiceDefinitionService.LIFECYCLE_ACTIVE,
                projected.getLifecycleState());
        assertEquals(1L, projected.getServiceGeneration());
        assertEquals(1, repository.saved);
    }

    private ServiceDefinitionService service(ServiceDefinitionRepository repository)
            throws Exception {
        ServiceDefinitionService service = new ServiceDefinitionService();
        Field field = ServiceDefinitionService.class.getDeclaredField("serviceRepository");
        field.setAccessible(true);
        field.set(service, repository);
        return service;
    }

    private static ServiceKey key(String serviceName) {
        return ServiceKey.of("public", "DEFAULT_GROUP", serviceName);
    }

    private static ServiceDefinition definition(
            long id, String serviceName, long generation, String lifecycle) {
        ServiceDefinition service = new ServiceDefinition();
        service.setId(id);
        service.setNamespaceId("public");
        service.setGroupName("DEFAULT_GROUP");
        service.setServiceName(serviceName);
        service.setServiceGeneration(generation);
        service.setLifecycleState(lifecycle);
        return service;
    }

    private static final class InMemoryRepository
            implements ServiceDefinitionRepository {
        private final Map<ServiceKey, ServiceDefinition> values = new LinkedHashMap<>();
        private int saved;
        private int updated;
        private boolean failNextSaveAsConcurrentInsert;

        @Override
        public ServiceDefinition find(ServiceKey key) {
            return values.get(key);
        }

        @Override
        public List<ServiceDefinition> findAll() {
            return List.copyOf(values.values());
        }

        @Override
        public List<ServiceDefinition> findByGroup(String namespaceId, String groupName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResult<ServiceDefinition> findPageByGroup(
                String namespaceId,
                String groupName,
                String keyword,
                String lifecycleState,
                PageQuery pageQuery) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ServiceDefinition> findByLifecycleState(
                String lifecycleState, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long save(ServiceDefinition service) {
            saved++;
            ServiceKey key = ServiceKey.of(
                    service.getNamespaceId(),
                    service.getGroupName(),
                    service.getServiceName());
            if (failNextSaveAsConcurrentInsert) {
                failNextSaveAsConcurrentInsert = false;
                ServiceDefinition concurrent = definition(
                        99L,
                        service.getServiceName(),
                        service.getServiceGeneration(),
                        service.getLifecycleState());
                concurrent.setCreatedBy(service.getCreatedBy());
                values.put(key, concurrent);
                throw new IllegalStateException("duplicate key");
            }
            service.setId((long) saved);
            values.put(key, service);
            return 1;
        }

        @Override
        public long update(ServiceDefinition service) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long updateLifecycle(ServiceDefinition service) {
            updated++;
            values.put(ServiceKey.of(
                    service.getNamespaceId(),
                    service.getGroupName(),
                    service.getServiceName()), service);
            return 1;
        }

        @Override
        public long delete(ServiceKey key) {
            throw new UnsupportedOperationException();
        }
    }
}
