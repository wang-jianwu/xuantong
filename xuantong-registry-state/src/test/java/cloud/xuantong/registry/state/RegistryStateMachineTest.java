package cloud.xuantong.registry.state;

import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.WatchBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryStateMachineTest {
    private static final RegistryActor CLIENT_A =
            new RegistryActor("tenant-a", "client-a", "orders");
    private static final RegistryActor CLIENT_B =
            new RegistryActor("tenant-a", "client-b", "orders");
    private static final ServiceKey SERVICE =
            new ServiceKey("public", "DEFAULT_GROUP", "orders");
    private static final InstanceKey INSTANCE = new InstanceKey(SERVICE, "orders-1");

    private StateGroupId groupId;
    private RegistryStateMachine stateMachine;
    private long logIndex;

    @BeforeEach
    void setUp() {
        groupId = StateGroupId.registry("registry-test");
        stateMachine = new RegistryStateMachine(groupId,
                new RegistryStateOptions(1_000, 60_000, 100, 100, 10, 2, 100));
        ApplyResult activated = apply("activate-service", new ActivateServiceDefinition(
                RegistryActor.system("management"), SERVICE, 0L, 500L));
        assertEquals(ApplyStatus.APPLIED, activated.status());
    }

    @Test
    void assignsAuthoritativeLeaseAndReplaysRegisterByOperationId() throws Exception {
        StateCommand command = RegistryStateCodec.mutationCommand(
                groupId,
                "register-1",
                register(CLIENT_A, "lease-server-1", 10_000, 1_000));

        ApplyResult first = apply(command);
        ApplyResult replay = apply(RegistryStateCodec.mutationCommand(
                groupId,
                "register-1",
                register(CLIENT_A, "lease-server-1", 10_000, 2_000)));

        assertEquals(ApplyStatus.APPLIED, first.status());
        assertEquals(ApplyStatus.UNCHANGED, replay.status());
        assertEquals(List.of(), RegistryStateCodec.decodeMutationResult(first.payload())
                .removedInstances());
        RegistryInstance instance = RegistryStateCodec.decodeMutationResult(first.payload())
                .instances().getFirst();
        assertEquals("lease-server-1", instance.leaseId());
        assertEquals(1, instance.leaseEpoch());
        assertEquals(1, instance.recoveryEpoch());
        assertEquals(0, instance.renewSequence());
        assertEquals(11_000, instance.expiresAtEpochMs());
        assertEquals(1, snapshot().registryRevision());
    }

    @Test
    void renewBatchIsAtomicAndSequenceFencesDelayedHeartbeats() throws Exception {
        RegistryInstance registered = registerAndRead(CLIENT_A, "lease-1", 1_000);
        LeaseReference lease = reference(registered);
        ApplyResult renewed = apply("renew-1", new RenewLeaseBatch(
                CLIENT_A,
                List.of(new LeaseRenewal(lease, 1, 10_000)),
                2_000));
        RegistryInstance after = RegistryStateCodec.decodeMutationResult(renewed.payload())
                .instances().getFirst();
        assertEquals(1, after.renewSequence());
        assertEquals(12_000, after.expiresAtEpochMs());
        assertEquals(1, snapshot().registryRevision(),
                "heartbeats must not churn Registry Watch revisions");

        ApplyResult delayed = apply("renew-delayed", new RenewLeaseBatch(
                CLIENT_A,
                List.of(new LeaseRenewal(lease, 1, 10_000)),
                3_000));
        assertEquals(ApplyStatus.REJECTED, delayed.status());
        assertEquals("RENEW_SEQUENCE_MISMATCH",
                RegistryStateCodec.decodeMutationError(delayed.payload()).code());
        assertEquals(1, leaseState().instance().renewSequence());
    }

    @Test
    void takeoverRotatesLeaseAndFencesOldOwnerHeartbeatAndDeregister() throws Exception {
        RegistryInstance original = registerAndRead(CLIENT_A, "lease-old", 1_000);
        ApplyResult takeover = apply("takeover-1", new TakeoverLease(
                CLIENT_B,
                reference(original),
                "lease-new",
                10_000,
                2_000));
        RegistryInstance replacement = RegistryStateCodec.decodeMutationResult(
                takeover.payload()).instances().getFirst();
        assertEquals("client-b", replacement.ownerClientInstanceId());
        assertEquals(2, replacement.leaseEpoch());
        assertEquals(2, replacement.recoveryEpoch());
        assertEquals("lease-new", replacement.leaseId());

        ApplyResult oldRenew = apply("old-renew", new RenewLeaseBatch(
                CLIENT_A,
                List.of(new LeaseRenewal(reference(original), 1, 10_000)),
                3_000));
        assertEquals("LEASE_FENCED",
                RegistryStateCodec.decodeMutationError(oldRenew.payload()).code());
        ApplyResult oldDelete = apply("old-delete", new DeregisterLease(
                CLIENT_A, reference(original), 3_001));
        assertEquals("LEASE_FENCED",
                RegistryStateCodec.decodeMutationError(oldDelete.payload()).code());
        assertTrue(leaseState().found());
        assertEquals("lease-new", leaseState().instance().leaseId());
    }

    @Test
    void takeoverCannotCrossApplicationOwnershipBoundary() throws Exception {
        RegistryInstance original = registerAndRead(CLIENT_A, "lease-old", 1_000);
        RegistryActor otherApplication = new RegistryActor(
                "tenant-a", "client-c", "billing");

        ApplyResult takeover = apply("cross-app-takeover", new TakeoverLease(
                otherApplication,
                reference(original),
                "lease-new",
                10_000,
                2_000));

        assertEquals(ApplyStatus.REJECTED, takeover.status());
        assertEquals("LEASE_FENCED",
                RegistryStateCodec.decodeMutationError(takeover.payload()).code());
        assertEquals("lease-old", leaseState().instance().leaseId());
        assertEquals("client-a", leaseState().instance().ownerClientInstanceId());
    }

    @Test
    void expiryUsesReplicatedLogicalTimeAndPreservesEpochHistory() throws Exception {
        RegistryInstance original = registerAndRead(CLIENT_A, "lease-1", 1_000);
        ApplyResult expired = apply("expire-1", new ExpireLeaseBatch(
                RegistryActor.system("state-1"), 100, original.expiresAtEpochMs()));
        RegistryMutationResult result = RegistryStateCodec.decodeMutationResult(
                expired.payload());
        assertEquals(List.of(INSTANCE), result.removedInstances());
        assertFalse(leaseState().found());

        RegistryInstance replacement = registerAndRead(CLIENT_B, "lease-2", 20_000);
        assertEquals(2, replacement.leaseEpoch());
        assertEquals(3, snapshot().registryRevision());
    }

    @Test
    void snapshotWatchCompactionAndRestorePreserveLeaseAndIdempotency() throws Exception {
        StateCommand register = RegistryStateCodec.mutationCommand(
                groupId, "register-1", register(CLIENT_A, "lease-1", 10_000, 1_000));
        apply(register);
        RegistryInstance first = leaseState().instance();
        apply("takeover", new TakeoverLease(
                CLIENT_B, reference(first), "lease-2", 10_000, 2_000));
        RegistryInstance second = leaseState().instance();
        apply("deregister", new DeregisterLease(CLIENT_B, reference(second), 3_000));

        WatchBatch compacted = stateMachine.watch(RegistryStateCodec.changesWatch(
                groupId,
                0,
                selector(),
                10,
                ReadOptions.linearizable()));
        assertTrue(compacted.resetRequired());
        WatchBatch current = stateMachine.watch(RegistryStateCodec.changesWatch(
                groupId,
                1,
                selector(),
                10,
                ReadOptions.linearizable()));
        assertFalse(current.resetRequired());
        assertEquals(2, current.events().size());
        assertEquals(3, current.coveredThrough().value());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        stateMachine.writeSnapshot(bytes);
        RegistryStateMachine restored = new RegistryStateMachine(groupId,
                new RegistryStateOptions(1_000, 60_000, 100, 100, 10, 2, 100));
        restored.installSnapshot(
                stateMachine.snapshotSchemaVersion(),
                new ByteArrayInputStream(bytes.toByteArray()));
        stateMachine = restored;
        assertEquals(3, snapshot().registryRevision());
        assertFalse(leaseState().found());
        assertEquals(ApplyStatus.UNCHANGED, apply(register).status());
        assertEquals(3, snapshot().registryRevision());
    }

    @Test
    void conflictingOperationIdIsRejectedWithoutMutatingLease() throws Exception {
        StateCommand first = RegistryStateCodec.mutationCommand(
                groupId, "same-op", register(CLIENT_A, "lease-1", 10_000, 1_000));
        apply(first);
        ApplyResult conflict = apply(RegistryStateCodec.mutationCommand(
                groupId, "same-op", register(CLIENT_A, "lease-other", 10_000, 1_000)));
        assertEquals(ApplyStatus.REJECTED, conflict.status());
        assertEquals("OPERATION_ID_CONFLICT",
                RegistryStateCodec.decodeMutationError(conflict.payload()).code());

        QueryResult resolved = stateMachine.query(RegistryStateCodec.resolveOperationQuery(
                groupId,
                new ResolveRegistryOperationRequest(CLIENT_A, "same-op"),
                ReadOptions.linearizable()));
        ResolvedRegistryOperation operation = RegistryStateCodec.decodeResolvedOperation(
                resolved.payload());
        assertTrue(operation.found());
        assertEquals(ApplyStatus.APPLIED, operation.status());
        assertEquals("lease-1", leaseState().instance().leaseId());
    }

    @Test
    void deletionFenceIsAtomicAndOldGenerationCannotRejoinRecreatedService()
            throws Exception {
        RegistryInstance registered = registerAndRead(CLIENT_A, "lease-1", 1_000);
        ApplyResult blocked = apply("delete-with-lease", new DeleteServiceDefinition(
                RegistryActor.system("management"), SERVICE, 1L, 2_000L));
        assertEquals(ApplyStatus.REJECTED, blocked.status());
        assertEquals("SERVICE_HAS_ACTIVE_LEASES",
                RegistryStateCodec.decodeMutationError(blocked.payload()).code());

        apply("deregister-before-delete", new DeregisterLease(
                CLIENT_A, reference(registered), 3_000L));
        ApplyResult deleted = apply("delete-service", new DeleteServiceDefinition(
                RegistryActor.system("management"), SERVICE, 1L, 4_000L));
        ServiceLifecycle tombstone = RegistryStateCodec.decodeMutationResult(
                deleted.payload()).services().getFirst();
        assertEquals(ServiceLifecycleStatus.DELETED, tombstone.status());
        assertEquals(1L, tombstone.generation());

        ApplyResult deletedRegister = apply("register-deleted",
                register(CLIENT_B, "lease-deleted", 10_000L, 5_000L, 1L));
        assertEquals("SERVICE_DEFINITION_NOT_ACTIVE",
                RegistryStateCodec.decodeMutationError(deletedRegister.payload()).code());

        ApplyResult reactivated = apply("reactivate-service", new ActivateServiceDefinition(
                RegistryActor.system("management"), SERVICE, 1L, 6_000L));
        assertEquals(2L, RegistryStateCodec.decodeMutationResult(
                reactivated.payload()).services().getFirst().generation());

        ApplyResult staleRegister = apply("register-old-generation",
                register(CLIENT_B, "lease-old-generation", 10_000L, 7_000L, 1L));
        assertEquals("SERVICE_GENERATION_FENCED",
                RegistryStateCodec.decodeMutationError(staleRegister.payload()).code());

        ApplyResult freshRegister = apply("register-current-generation",
                register(CLIENT_B, "lease-current-generation", 10_000L, 8_000L, 0L));
        assertEquals(2L, RegistryStateCodec.decodeMutationResult(freshRegister.payload())
                .instances().getFirst().registration().serviceGeneration());
    }

    @Test
    void snapshotRestorePreservesDeletedServiceFence() throws Exception {
        ApplyResult deleted = apply("delete-empty-service", new DeleteServiceDefinition(
                RegistryActor.system("management"), SERVICE, 1L, 1_000L));
        assertEquals(ApplyStatus.APPLIED, deleted.status());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        stateMachine.writeSnapshot(bytes);
        RegistryStateMachine restored = new RegistryStateMachine(groupId,
                new RegistryStateOptions(1_000, 60_000, 100, 100, 10, 2, 100));
        restored.installSnapshot(
                stateMachine.snapshotSchemaVersion(),
                new ByteArrayInputStream(bytes.toByteArray()));
        stateMachine = restored;

        assertEquals(ServiceLifecycleStatus.DELETED, lifecycle().lifecycle().status());
        ApplyResult rejected = apply("register-after-restore",
                register(CLIENT_A, "lease-after-restore", 10_000L, 2_000L, 1L));
        assertEquals("SERVICE_DEFINITION_NOT_ACTIVE",
                RegistryStateCodec.decodeMutationError(rejected.payload()).code());
        assertEquals(ApplyStatus.UNCHANGED,
                apply("delete-empty-service", new DeleteServiceDefinition(
                        RegistryActor.system("management"), SERVICE, 1L, 3_000L)).status());
    }

    @Test
    void lifecycleSnapshotIncludesActiveAndDeletedServiceFences() throws Exception {
        ServiceKey deletedService = new ServiceKey(
                "public", "DEFAULT_GROUP", "billing");
        apply("activate-billing", new ActivateServiceDefinition(
                RegistryActor.system("management"), deletedService, 0L, 600L));
        apply("delete-billing", new DeleteServiceDefinition(
                RegistryActor.system("management"), deletedService, 1L, 700L));

        QueryResult result = stateMachine.query(
                RegistryStateCodec.serviceLifecycleSnapshotQuery(
                        groupId, ReadOptions.linearizable()));
        ServiceLifecycleSnapshot snapshot =
                RegistryStateCodec.decodeServiceLifecycleSnapshot(result.payload());

        assertEquals(RegistryStateCodec.RESULT_SERVICE_LIFECYCLE_SNAPSHOT,
                result.resultType());
        assertEquals(2, snapshot.services().size());
        assertEquals(ServiceLifecycleStatus.DELETED,
                snapshot.services().getFirst().status());
        assertEquals(ServiceLifecycleStatus.ACTIVE,
                snapshot.services().get(1).status());
        assertTrue(snapshot.serverTimeEpochMs() >= 700L);
    }

    @Test
    void lifecycleSnapshotPagesByServiceKey() throws Exception {
        ServiceKey billing = new ServiceKey("public", "DEFAULT_GROUP", "billing");
        ServiceKey payments = new ServiceKey("public", "DEFAULT_GROUP", "payments");
        apply("activate-billing-page", new ActivateServiceDefinition(
                RegistryActor.system("management"), billing, 0L, 600L));
        apply("activate-payments-page", new ActivateServiceDefinition(
                RegistryActor.system("management"), payments, 0L, 700L));

        ServiceLifecycleSnapshot first = RegistryStateCodec.decodeServiceLifecycleSnapshot(
                stateMachine.query(RegistryStateCodec.serviceLifecycleSnapshotQuery(
                        groupId,
                        new ServiceLifecycleSnapshotRequest(null, 2),
                        ReadOptions.linearizable())).payload());
        ServiceLifecycleSnapshot second = RegistryStateCodec.decodeServiceLifecycleSnapshot(
                stateMachine.query(RegistryStateCodec.serviceLifecycleSnapshotQuery(
                        groupId,
                        new ServiceLifecycleSnapshotRequest(
                                first.services().getLast().serviceKey(), 2),
                        ReadOptions.linearizable())).payload());

        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertEquals(List.of(billing, SERVICE), first.services().stream()
                .map(ServiceLifecycle::serviceKey).toList());
        assertEquals(List.of(payments), second.services().stream()
                .map(ServiceLifecycle::serviceKey).toList());
    }

    private RegistryInstance registerAndRead(
            RegistryActor actor, String leaseId, long observedTime) throws Exception {
        ApplyResult result = apply("register-" + leaseId,
                register(actor, leaseId, 10_000, observedTime));
        assertEquals(ApplyStatus.APPLIED, result.status());
        return RegistryStateCodec.decodeMutationResult(result.payload())
                .instances().getFirst();
    }

    private RegisterLease register(
            RegistryActor actor,
            String leaseId,
            long ttlMs,
            long observedTime) {
        ServiceRegistration registration = new ServiceRegistration(
                INSTANCE, 0L, "10.0.0.1", 8080, 1D, true, "{\"zone\":\"a\"}");
        return new RegisterLease(actor, registration, leaseId, ttlMs, observedTime);
    }

    private RegisterLease register(
            RegistryActor actor,
            String leaseId,
            long ttlMs,
            long observedTime,
            long serviceGeneration) {
        ServiceRegistration registration = new ServiceRegistration(
                INSTANCE,
                serviceGeneration,
                "10.0.0.1",
                8080,
                1D,
                true,
                "{\"zone\":\"a\"}");
        return new RegisterLease(actor, registration, leaseId, ttlMs, observedTime);
    }

    private LeaseReference reference(RegistryInstance instance) {
        return new LeaseReference(
                instance.instanceKey(),
                instance.leaseId(),
                instance.leaseEpoch(),
                instance.recoveryEpoch());
    }

    private RegistrySnapshot snapshot() throws Exception {
        QueryResult result = stateMachine.query(RegistryStateCodec.snapshotQuery(
                groupId, selector(), ReadOptions.linearizable()));
        return RegistryStateCodec.decodeSnapshot(result.payload());
    }

    private LeaseState leaseState() throws Exception {
        QueryResult result = stateMachine.query(RegistryStateCodec.leaseStateQuery(
                groupId,
                new GetLeaseStateRequest(INSTANCE),
                ReadOptions.linearizable()));
        return RegistryStateCodec.decodeLeaseState(result.payload());
    }

    private ServiceLifecycleState lifecycle() throws Exception {
        QueryResult result = stateMachine.query(RegistryStateCodec.serviceLifecycleQuery(
                groupId,
                new GetServiceLifecycleRequest(SERVICE),
                ReadOptions.linearizable()));
        return RegistryStateCodec.decodeServiceLifecycleState(result.payload());
    }

    private RegistrySnapshotRequest selector() {
        return new RegistrySnapshotRequest(
                "public", "DEFAULT_GROUP", List.of("orders"));
    }

    private ApplyResult apply(String operationId, RegistryMutation mutation) {
        return apply(RegistryStateCodec.mutationCommand(groupId, operationId, mutation));
    }

    private ApplyResult apply(StateCommand command) {
        return stateMachine.apply(command, new ApplyContext(groupId, 1, ++logIndex));
    }
}
