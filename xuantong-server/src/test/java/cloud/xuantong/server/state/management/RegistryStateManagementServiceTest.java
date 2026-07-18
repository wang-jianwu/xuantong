package cloud.xuantong.server.state.management;

import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.registry.state.DeregisterLease;
import cloud.xuantong.registry.state.InstanceKey;
import cloud.xuantong.registry.state.LeaseReference;
import cloud.xuantong.registry.state.RegisterLease;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryInstance;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.RegistryStateMachine;
import cloud.xuantong.registry.state.ServiceKey;
import cloud.xuantong.registry.state.ServiceLifecycleStatus;
import cloud.xuantong.registry.state.ServiceRegistration;
import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryStateManagementServiceTest {
    @Test
    void permitsLifecycleProjectionWhenRegistryStateIsExplicitlyDisabled() {
        RegistryStateManagementService service = new RegistryStateManagementService(
                new ControlStatePlaneRuntime(),
                new RegistryStatePlaneProperties(false, "registry-test", 1_000, 60_000));

        assertEquals(0L, service.activateServiceDefinition(
                "public", "DEFAULT_GROUP", "orders", "activate-orders"));
        assertDoesNotThrow(() -> service.deleteServiceDefinition(
                "public", "DEFAULT_GROUP", "orders", 0L, "delete-orders"));
    }

    @Test
    void failsClosedWhenEnabledRegistryStateIsUnavailable() {
        RegistryStateManagementService service = new RegistryStateManagementService(
                new ControlStatePlaneRuntime(),
                new RegistryStatePlaneProperties(true, "registry-test", 1_000, 60_000));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.deleteServiceDefinition(
                        "public", "DEFAULT_GROUP", "orders", 1L, "delete-orders"));

        assertEquals("Registry State Plane is disabled or unavailable", failure.getMessage());
    }

    @Test
    void lifecycleDeleteUsesTheAtomicRegistryFence() throws Exception {
        StateGroupId groupId = StateGroupId.registry("registry-test");
        InMemoryStateClient stateClient = new InMemoryStateClient(
                new RegistryStateMachine(groupId));
        RegistryStateManagementService service = new RegistryStateManagementService(
                stateClient,
                new RegistryStatePlaneProperties(
                        true, "registry-test", 1_000, 60_000));

        long generation = service.activateServiceDefinition(
                "public", "DEFAULT_GROUP", "orders", "activate-orders");
        RegistryActor provider = new RegistryActor("tenant-a", "orders-1", "orders");
        InstanceKey instanceKey = new InstanceKey(
                new ServiceKey("public", "DEFAULT_GROUP", "orders"),
                "orders-1");
        ApplyResult registered = stateClient.submit(RegistryStateCodec.mutationCommand(
                        groupId,
                        "register-orders-1",
                        new RegisterLease(
                                provider,
                                new ServiceRegistration(
                                        instanceKey,
                                        generation,
                                        "10.0.0.8",
                                        8080,
                                        1D,
                                        true,
                                        ""),
                                "lease-orders-1",
                                30_000,
                                1_000)))
                .toCompletableFuture()
                .join();
        RegistryInstance lease = RegistryStateCodec.decodeMutationResult(
                registered.payload()).instances().getFirst();

        RegistryLifecycleMutationException blocked = assertThrows(
                RegistryLifecycleMutationException.class,
                () -> service.deleteServiceDefinition(
                        "public",
                        "DEFAULT_GROUP",
                        "orders",
                        generation,
                        "delete-orders-with-lease"));
        assertEquals(RegistryLifecycleMutationException.Reason.ACTIVE_LEASES,
                blocked.reason());

        stateClient.submit(RegistryStateCodec.mutationCommand(
                        groupId,
                        "deregister-orders-1",
                        new DeregisterLease(
                                provider,
                                new LeaseReference(
                                        instanceKey,
                                        lease.leaseId(),
                                        lease.leaseEpoch(),
                                        lease.recoveryEpoch()),
                                2_000)))
                .toCompletableFuture()
                .join();
        service.deleteServiceDefinition(
                "public",
                "DEFAULT_GROUP",
                "orders",
                generation,
                "delete-orders-empty");

        assertEquals(ServiceLifecycleStatus.DELETED,
                service.serviceLifecycle(
                        "public", "DEFAULT_GROUP", "orders")
                        .lifecycle()
                        .status());
    }

    private static final class InMemoryStateClient implements StateClient {
        private final RegistryStateMachine stateMachine;
        private final AtomicLong logIndex = new AtomicLong();

        private InMemoryStateClient(RegistryStateMachine stateMachine) {
            this.stateMachine = stateMachine;
        }

        @Override
        public CompletionStage<ApplyResult> submit(StateCommand command) {
            return CompletableFuture.completedFuture(stateMachine.apply(
                    command,
                    new ApplyContext(
                            command.groupId(), 1L, logIndex.incrementAndGet())));
        }

        @Override
        public CompletionStage<QueryResult> query(StateQuery query) {
            return CompletableFuture.completedFuture(stateMachine.query(query));
        }

        @Override
        public CompletionStage<WatchBatch> watch(WatchRequest request) {
            return CompletableFuture.completedFuture(stateMachine.watch(request));
        }

        @Override
        public void close() {
        }
    }
}
