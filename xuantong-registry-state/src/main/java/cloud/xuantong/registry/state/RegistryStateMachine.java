package cloud.xuantong.registry.state;

import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import cloud.xuantong.state.api.StateMachine;
import cloud.xuantong.state.api.StateMachineCompatibility;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchEvent;
import cloud.xuantong.state.api.WatchRequest;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Deterministic authoritative discovery lease state machine.
 *
 * <p>The state machine never reads wall time. A trusted Gateway or the expiry
 * coordinator proposes an observed epoch value inside the replicated command;
 * apply advances one monotonic logical clock with that committed value.</p>
 */
public final class RegistryStateMachine implements StateMachine {
    public static final int SNAPSHOT_SCHEMA_VERSION = 2;
    private static final int SNAPSHOT_MAGIC = 0x58525332;

    private final StateGroupId groupId;
    private final RegistryStateOptions options;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    private final NavigableMap<ServiceKey, ServiceLifecycle> services = new TreeMap<>();
    private final NavigableMap<InstanceKey, RegistryInstance> instances = new TreeMap<>();
    private final NavigableMap<InstanceKey, Long> leaseEpochs = new TreeMap<>();
    private final NavigableMap<Long, RegistryChangeEvent> changeLog = new TreeMap<>();
    private final Map<OperationKey, OperationRecord> operations = new LinkedHashMap<>();

    private long appliedIndex;
    private long logicalTimeEpochMs;
    private long registryRevision;
    private long compactionRevision;

    public RegistryStateMachine(StateGroupId groupId) {
        this(groupId, RegistryStateOptions.defaults());
    }

    public RegistryStateMachine(StateGroupId groupId, RegistryStateOptions options) {
        if (groupId == null || groupId.type() != StateGroupType.REGISTRY) {
            throw new IllegalArgumentException("RegistryStateMachine requires a REGISTRY group");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        this.groupId = groupId;
        this.options = options;
    }

    @Override
    public StateGroupId groupId() {
        return groupId;
    }

    @Override
    public ApplyResult apply(StateCommand command, ApplyContext context) {
        requireGroup(command.groupId());
        if (!groupId.equals(context.groupId())) {
            throw new IllegalArgumentException("Apply context belongs to another State Group");
        }
        stateLock.writeLock().lock();
        try {
            appliedIndex = context.logIndex();
            if (!RegistryStateCodec.COMMAND_MUTATE.equals(command.commandType())
                    || command.schemaVersion() != RegistryStateCodec.SCHEMA_VERSION) {
                return rejected(command.operationId(), "UNSUPPORTED_COMMAND",
                        "Unsupported Registry State command or schema version");
            }
            if (command.operationId().length() > 256) {
                return rejected(command.operationId(), "INVALID_OPERATION_ID",
                        "operationId must not exceed 256 characters");
            }

            RegistryMutation mutation;
            try {
                mutation = RegistryStateCodec.decodeMutation(command.payload());
            } catch (IOException | IllegalArgumentException e) {
                return rejected(command.operationId(), "MALFORMED_COMMAND", safeMessage(e));
            }
            String requestHash = RegistryStateCodec.requestHash(command);
            if (mutation instanceof ExpireLeaseBatch) {
                OperationRecord result = mutate(mutation, requestHash);
                return result.toApplyResult(
                        groupId, command.operationId(), appliedIndex, result.status());
            }
            OperationKey operationKey = new OperationKey(
                    mutation.actor().idempotencyScope(), command.operationId());
            OperationRecord existing = operations.get(operationKey);
            if (existing != null) {
                if (!MessageDigest.isEqual(
                        existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                        requestHash.getBytes(StandardCharsets.US_ASCII))) {
                    return rejected(command.operationId(), "OPERATION_ID_CONFLICT",
                            "operationId was already committed with another request");
                }
                ApplyStatus replayStatus = existing.status() == ApplyStatus.APPLIED
                        ? ApplyStatus.UNCHANGED : existing.status();
                return existing.toApplyResult(
                        groupId, command.operationId(), appliedIndex, replayStatus);
            }
            OperationRecord result = mutate(mutation, requestHash);
            compactOperationHistory();
            operations.put(operationKey, result);
            return result.toApplyResult(
                    groupId, command.operationId(), appliedIndex, result.status());
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public QueryResult query(StateQuery query) {
        requireGroup(query.groupId());
        requireSchema(query.schemaVersion());
        stateLock.readLock().lock();
        try {
            return switch (query.queryType()) {
                case RegistryStateCodec.QUERY_SNAPSHOT -> snapshot(query);
                case RegistryStateCodec.QUERY_LEASE_STATE -> leaseState(query);
                case RegistryStateCodec.QUERY_SERVICE_LIFECYCLE -> serviceLifecycle(query);
                case RegistryStateCodec.QUERY_SERVICE_LIFECYCLE_SNAPSHOT ->
                        serviceLifecycleSnapshot(query);
                case RegistryStateCodec.QUERY_RESOLVE_OPERATION -> resolveOperation(query);
                case RegistryStateCodec.QUERY_OVERVIEW -> overview(query);
                default -> throw new IllegalArgumentException(
                        "Unsupported Registry State query: " + query.queryType());
            };
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public WatchBatch watch(WatchRequest request) {
        requireGroup(request.groupId());
        requireSchema(request.schemaVersion());
        if (!RegistryStateCodec.WATCH_CHANGES.equals(request.watchType())) {
            throw new IllegalArgumentException(
                    "Unsupported Registry State Watch: " + request.watchType());
        }
        stateLock.readLock().lock();
        try {
            long after = request.afterRevision().value();
            if (after > registryRevision) {
                throw new IllegalArgumentException(
                        "Watch cursor is ahead of the Registry high watermark");
            }
            if (after < compactionRevision) {
                return new WatchBatch(
                        request.afterRevision(),
                        StateRevision.registry(groupId, registryRevision),
                        StateRevision.registry(groupId, compactionRevision),
                        true,
                        List.of());
            }
            RegistrySnapshotRequest selector;
            try {
                selector = RegistryStateCodec.decodeSnapshotRequest(request.selector());
            } catch (IOException e) {
                throw new IllegalArgumentException("Malformed Registry Watch selector", e);
            }
            List<WatchEvent> events = new ArrayList<>();
            long coveredThrough = after;
            for (RegistryChangeEvent event : changeLog.tailMap(after, false).values()) {
                boolean matches = selector.matches(event.instanceKey());
                if (matches && events.size() >= request.maxBatchSize()) {
                    break;
                }
                coveredThrough = event.registryRevision();
                if (matches) {
                    events.add(new WatchEvent(
                            StateRevision.registry(groupId, event.registryRevision()),
                            RegistryStateCodec.EVENT_INSTANCE_CHANGED,
                            RegistryStateCodec.SCHEMA_VERSION,
                            RegistryStateCodec.encodeChangeEvent(event)));
                }
            }
            if (events.size() < request.maxBatchSize()) {
                coveredThrough = registryRevision;
            }
            return new WatchBatch(
                    request.afterRevision(),
                    StateRevision.registry(groupId, coveredThrough),
                    StateRevision.registry(groupId, compactionRevision),
                    false,
                    events);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private OperationRecord mutate(RegistryMutation mutation, String requestHash) {
        advanceLogicalTime(mutation.observedTimeEpochMs());
        try {
            if (mutation instanceof RegisterLease register) {
                return register(register, requestHash);
            }
            if (mutation instanceof RenewLeaseBatch renew) {
                return renew(renew, requestHash);
            }
            if (mutation instanceof DeregisterLease deregister) {
                return deregister(deregister, requestHash);
            }
            if (mutation instanceof TakeoverLease takeover) {
                return takeover(takeover, requestHash);
            }
            if (mutation instanceof ExpireLeaseBatch expire) {
                return expire(expire, requestHash);
            }
            if (mutation instanceof EvictLease evict) {
                return evict(evict, requestHash);
            }
            if (mutation instanceof ActivateServiceDefinition activate) {
                return activateService(activate, requestHash);
            }
            if (mutation instanceof DeleteServiceDefinition delete) {
                return deleteService(delete, requestHash);
            }
            return operationError(requestHash, "UNSUPPORTED_MUTATION",
                    "Unsupported Registry mutation");
        } catch (MutationRejected e) {
            return operationError(requestHash, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            return operationError(requestHash, "INVALID_MUTATION", safeMessage(e));
        }
    }

    private OperationRecord register(RegisterLease command, String requestHash) {
        validateTtl(command.ttlMs());
        InstanceKey key = command.registration().instanceKey();
        ServiceLifecycle lifecycle = services.get(key.service());
        if (lifecycle != null && !lifecycle.active()) {
            throw reject("SERVICE_DEFINITION_NOT_ACTIVE",
                    "Service definition was explicitly deleted and must be reactivated: "
                            + key.service().canonicalName());
        }
        long committedGeneration = lifecycle == null ? 1L : lifecycle.generation();
        long expectedGeneration = command.registration().serviceGeneration();
        if (expectedGeneration != 0 && expectedGeneration != committedGeneration) {
            throw reject("SERVICE_GENERATION_FENCED",
                    "Service definition generation was fenced: expected="
                            + expectedGeneration + ", current=" + committedGeneration);
        }
        RegistryInstance existing = instances.get(key);
        if (existing != null && !existing.expiredAt(logicalTimeEpochMs)) {
            throw reject("INSTANCE_ALREADY_OWNED",
                    "Service instance already has an active lease: " + key.canonicalName());
        }
        if (existing == null && instances.size() >= options.maxInstances()) {
            throw reject("REGISTRY_CAPACITY_EXCEEDED",
                    "Registry instance capacity has been reached");
        }
        if (lifecycle == null) {
            if (services.size() >= options.maxServices()) {
                throw reject("SERVICE_CAPACITY_EXCEEDED",
                        "Registry service lifecycle capacity has been reached");
            }
            lifecycle = new ServiceLifecycle(
                    key.service(),
                    committedGeneration,
                    ServiceLifecycleStatus.ACTIVE,
                    logicalTimeEpochMs);
            services.put(key.service(), lifecycle);
        }
        long nextLeaseEpoch = nextEpoch(key);
        ServiceRegistration committedRegistration =
                command.registration().withServiceGeneration(committedGeneration);
        RegistryInstance registered = new RegistryInstance(
                committedRegistration,
                command.proposedLeaseId(),
                nextLeaseEpoch,
                1L,
                0L,
                command.actor().clientInstanceId(),
                command.actor().applicationName(),
                logicalTimeEpochMs,
                logicalTimeEpochMs,
                expiresAt(command.ttlMs()));
        instances.put(key, registered);
        leaseEpochs.put(key, nextLeaseEpoch);
        appendChange(existing == null ? "INSTANCE_REGISTERED" : "INSTANCE_REPLACED",
                key, registered);
        return success(
                requestHash,
                "REGISTER",
                List.of(lifecycle),
                List.of(registered),
                List.of());
    }

    private OperationRecord activateService(
            ActivateServiceDefinition command, String requestHash) {
        ServiceLifecycle existing = services.get(command.serviceKey());
        long currentGeneration = existing == null ? 0L : existing.generation();
        if (existing != null && existing.active()) {
            throw reject("SERVICE_DEFINITION_ALREADY_ACTIVE",
                    "Service definition is already active: "
                            + command.serviceKey().canonicalName());
        }
        if (command.expectedPreviousGeneration() != currentGeneration) {
            throw reject("SERVICE_GENERATION_FENCED",
                    "Service definition generation was fenced: expected="
                            + command.expectedPreviousGeneration()
                            + ", current=" + currentGeneration);
        }
        if (existing == null && services.size() >= options.maxServices()) {
            throw reject("SERVICE_CAPACITY_EXCEEDED",
                    "Registry service lifecycle capacity has been reached");
        }
        ServiceLifecycle activated = new ServiceLifecycle(
                command.serviceKey(),
                increment(currentGeneration, "serviceGeneration"),
                ServiceLifecycleStatus.ACTIVE,
                logicalTimeEpochMs);
        services.put(command.serviceKey(), activated);
        return serviceSuccess(requestHash, "ACTIVATE_SERVICE", activated);
    }

    private OperationRecord deleteService(
            DeleteServiceDefinition command, String requestHash) {
        ServiceLifecycle existing = services.get(command.serviceKey());
        if (existing == null || !existing.active()) {
            throw reject("SERVICE_DEFINITION_NOT_ACTIVE",
                    "Service definition is not active: "
                            + command.serviceKey().canonicalName());
        }
        if (existing.generation() != command.expectedGeneration()) {
            throw reject("SERVICE_GENERATION_FENCED",
                    "Service definition generation was fenced: expected="
                            + command.expectedGeneration()
                            + ", current=" + existing.generation());
        }
        boolean activeLease = instances.values().stream()
                .anyMatch(instance -> command.serviceKey().equals(instance.instanceKey().service())
                        && !instance.expiredAt(logicalTimeEpochMs));
        if (activeLease) {
            throw reject("SERVICE_HAS_ACTIVE_LEASES",
                    "Service definition still has active leases: "
                            + command.serviceKey().canonicalName());
        }
        ServiceLifecycle deleted = new ServiceLifecycle(
                command.serviceKey(),
                existing.generation(),
                ServiceLifecycleStatus.DELETED,
                logicalTimeEpochMs);
        services.put(command.serviceKey(), deleted);
        return serviceSuccess(requestHash, "DELETE_SERVICE", deleted);
    }

    private OperationRecord renew(RenewLeaseBatch command, String requestHash) {
        if (command.renewals().size() > options.maxRenewBatchSize()) {
            throw reject("RENEW_BATCH_TOO_LARGE",
                    "Renew batch exceeds " + options.maxRenewBatchSize());
        }
        List<RegistryInstance> current = new ArrayList<>(command.renewals().size());
        for (LeaseRenewal renewal : command.renewals()) {
            validateTtl(renewal.ttlMs());
            RegistryInstance instance = requireLease(
                    command.actor(), renewal.lease(), true);
            if (renewal.renewSequence() != instance.renewSequence() + 1) {
                throw reject("RENEW_SEQUENCE_MISMATCH",
                        "renewSequence must be exactly one greater than the committed value");
            }
            current.add(instance);
        }
        List<RegistryInstance> renewed = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            RegistryInstance before = current.get(i);
            LeaseRenewal renewal = command.renewals().get(i);
            RegistryInstance after = new RegistryInstance(
                    before.registration(),
                    before.leaseId(),
                    before.leaseEpoch(),
                    before.recoveryEpoch(),
                    renewal.renewSequence(),
                    before.ownerClientInstanceId(),
                    before.ownerApplicationName(),
                    before.registeredAtEpochMs(),
                    logicalTimeEpochMs,
                    expiresAt(renewal.ttlMs()));
            instances.put(after.instanceKey(), after);
            renewed.add(after);
        }
        return success(requestHash, "RENEW_BATCH", renewed, List.of());
    }

    private OperationRecord deregister(DeregisterLease command, String requestHash) {
        RegistryInstance existing = requireLease(command.actor(), command.lease(), true);
        instances.remove(existing.instanceKey());
        appendChange("INSTANCE_DEREGISTERED", existing.instanceKey(), null);
        return success(requestHash, "DEREGISTER", List.of(), List.of(existing.instanceKey()));
    }

    private OperationRecord takeover(TakeoverLease command, String requestHash) {
        validateTtl(command.ttlMs());
        RegistryInstance existing = requireLease(
                null, command.expectedLease(), true);
        if (!existing.ownerApplicationName().equals(command.actor().applicationName())) {
            throw reject("LEASE_FENCED",
                    "Service instance lease belongs to another application");
        }
        long nextLeaseEpoch = increment(existing.leaseEpoch(), "leaseEpoch");
        long nextRecoveryEpoch = increment(existing.recoveryEpoch(), "recoveryEpoch");
        RegistryInstance replacement = new RegistryInstance(
                existing.registration(),
                command.proposedLeaseId(),
                nextLeaseEpoch,
                nextRecoveryEpoch,
                0L,
                command.actor().clientInstanceId(),
                command.actor().applicationName(),
                existing.registeredAtEpochMs(),
                logicalTimeEpochMs,
                expiresAt(command.ttlMs()));
        instances.put(existing.instanceKey(), replacement);
        leaseEpochs.put(existing.instanceKey(), nextLeaseEpoch);
        appendChange("INSTANCE_TAKEN_OVER", existing.instanceKey(), replacement);
        return success(requestHash, "TAKEOVER_AND_RENEW", List.of(replacement), List.of());
    }

    private OperationRecord expire(ExpireLeaseBatch command, String requestHash) {
        List<InstanceKey> removed = new ArrayList<>();
        for (Map.Entry<InstanceKey, RegistryInstance> entry : instances.entrySet()) {
            if (removed.size() >= command.maxExpirations()) {
                break;
            }
            if (entry.getValue().expiredAt(logicalTimeEpochMs)) {
                removed.add(entry.getKey());
            }
        }
        for (InstanceKey key : removed) {
            instances.remove(key);
            appendChange("INSTANCE_EXPIRED", key, null);
        }
        return success(requestHash, "EXPIRE_BATCH", List.of(), removed);
    }

    private OperationRecord evict(EvictLease command, String requestHash) {
        RegistryInstance existing = requireLease(null, command.expectedLease(), true);
        instances.remove(existing.instanceKey());
        appendChange("INSTANCE_EVICTED", existing.instanceKey(), null);
        return success(requestHash, "EVICT", List.of(), List.of(existing.instanceKey()));
    }

    private QueryResult snapshot(StateQuery query) {
        RegistrySnapshotRequest request;
        try {
            request = RegistryStateCodec.decodeSnapshotRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed Registry snapshot query", e);
        }
        List<RegistryInstance> selected = instances.values().stream()
                .filter(instance -> request.matches(instance.instanceKey()))
                .filter(instance -> !instance.expiredAt(logicalTimeEpochMs))
                .toList();
        RegistrySnapshot snapshot = new RegistrySnapshot(
                registryRevision,
                compactionRevision,
                logicalTimeEpochMs,
                selected);
        return queryResult(
                RegistryStateCodec.RESULT_SNAPSHOT,
                RegistryStateCodec.encodeSnapshot(snapshot));
    }

    private QueryResult leaseState(StateQuery query) {
        GetLeaseStateRequest request;
        try {
            request = RegistryStateCodec.decodeLeaseStateRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed Registry lease-state query", e);
        }
        RegistryInstance instance = instances.get(request.instanceKey());
        if (instance != null && instance.expiredAt(logicalTimeEpochMs)) {
            instance = null;
        }
        return queryResult(
                RegistryStateCodec.RESULT_LEASE_STATE,
                RegistryStateCodec.encodeLeaseState(new LeaseState(
                        instance != null,
                        registryRevision,
                        logicalTimeEpochMs,
                        instance)));
    }

    private QueryResult serviceLifecycle(StateQuery query) {
        GetServiceLifecycleRequest request;
        try {
            request = RegistryStateCodec.decodeServiceLifecycleRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Malformed Registry service-lifecycle query", e);
        }
        ServiceLifecycle lifecycle = services.get(request.serviceKey());
        return queryResult(
                RegistryStateCodec.RESULT_SERVICE_LIFECYCLE,
                RegistryStateCodec.encodeServiceLifecycleState(
                        new ServiceLifecycleState(
                                lifecycle != null,
                                registryRevision,
                                logicalTimeEpochMs,
                                lifecycle)));
    }

    private QueryResult serviceLifecycleSnapshot(StateQuery query) {
        ServiceLifecycleSnapshotRequest request;
        try {
            request = RegistryStateCodec.decodeServiceLifecycleSnapshotRequest(
                    query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Malformed Registry lifecycle snapshot query", e);
        }
        NavigableMap<ServiceKey, ServiceLifecycle> selected =
                request.afterExclusive() == null
                        ? services
                        : services.tailMap(request.afterExclusive(), false);
        List<ServiceLifecycle> page = selected.values().stream()
                .limit((long) request.limit() + 1L)
                .toList();
        boolean hasMore = page.size() > request.limit();
        if (hasMore) {
            page = page.subList(0, request.limit());
        }
        ServiceLifecycleSnapshot snapshot = new ServiceLifecycleSnapshot(
                registryRevision,
                logicalTimeEpochMs,
                page,
                hasMore);
        return queryResult(
                RegistryStateCodec.RESULT_SERVICE_LIFECYCLE_SNAPSHOT,
                RegistryStateCodec.encodeServiceLifecycleSnapshot(snapshot));
    }

    private QueryResult resolveOperation(StateQuery query) {
        ResolveRegistryOperationRequest request;
        try {
            request = RegistryStateCodec.decodeResolveOperationRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed Registry resolve-operation query", e);
        }
        OperationRecord record = operations.get(new OperationKey(
                request.actor().idempotencyScope(), request.operationId()));
        ResolvedRegistryOperation resolved = record == null
                ? ResolvedRegistryOperation.missing() : record.resolved();
        return queryResult(
                RegistryStateCodec.RESULT_RESOLVED_OPERATION,
                RegistryStateCodec.encodeResolvedOperation(resolved));
    }

    private QueryResult overview(StateQuery query) {
        if (query.payload().length != 0) {
            throw new IllegalArgumentException("Registry overview query has no payload");
        }
        List<RegistryInstance> active = instances.values().stream()
                .filter(instance -> !instance.expiredAt(logicalTimeEpochMs))
                .toList();
        long services = active.stream()
                .map(instance -> instance.instanceKey().service())
                .distinct()
                .count();
        return queryResult(
                RegistryStateCodec.RESULT_OVERVIEW,
                RegistryStateCodec.encodeOverview(new RegistryOverview(
                        registryRevision,
                        logicalTimeEpochMs,
                        active.size(),
                        services)));
    }

    private QueryResult queryResult(String resultType, byte[] payload) {
        return new QueryResult(
                groupId,
                appliedIndex,
                false,
                resultType,
                payload,
                List.of(StateRevision.registry(groupId, registryRevision)));
    }

    private RegistryInstance requireLease(
            RegistryActor actor, LeaseReference lease, boolean requireActive) {
        RegistryInstance existing = instances.get(lease.instanceKey());
        if (existing == null) {
            throw reject("LEASE_EXPIRED", "Service instance lease does not exist");
        }
        if (requireActive && existing.expiredAt(logicalTimeEpochMs)) {
            throw reject("LEASE_EXPIRED", "Service instance lease has expired");
        }
        if (!MessageDigest.isEqual(
                existing.leaseId().getBytes(StandardCharsets.UTF_8),
                lease.leaseId().getBytes(StandardCharsets.UTF_8))
                || existing.leaseEpoch() != lease.leaseEpoch()
                || existing.recoveryEpoch() != lease.recoveryEpoch()) {
            throw reject("LEASE_FENCED", "Service instance lease was fenced");
        }
        if (actor != null
                && !existing.ownerClientInstanceId().equals(actor.clientInstanceId())) {
            throw reject("LEASE_FENCED", "Service instance lease belongs to another client");
        }
        return existing;
    }

    private void validateTtl(long ttlMs) {
        if (ttlMs < options.minLeaseTtlMs() || ttlMs > options.maxLeaseTtlMs()) {
            throw reject("INVALID_TTL", "Lease TTL must be between "
                    + options.minLeaseTtlMs() + " and " + options.maxLeaseTtlMs());
        }
    }

    private long nextEpoch(InstanceKey key) {
        long current = leaseEpochs.getOrDefault(key, 0L);
        return increment(current, "leaseEpoch");
    }

    private long expiresAt(long ttlMs) {
        return logicalTimeEpochMs > Long.MAX_VALUE - ttlMs
                ? Long.MAX_VALUE : logicalTimeEpochMs + ttlMs;
    }

    private static long increment(long value, String field) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException(field + " overflow");
        }
        return value + 1;
    }

    private void advanceLogicalTime(long observedTimeEpochMs) {
        if (observedTimeEpochMs > logicalTimeEpochMs) {
            logicalTimeEpochMs = observedTimeEpochMs;
        } else if (logicalTimeEpochMs < Long.MAX_VALUE) {
            logicalTimeEpochMs++;
        }
    }

    private void appendChange(
            String eventType, InstanceKey key, RegistryInstance instance) {
        registryRevision = increment(registryRevision, "registryRevision");
        changeLog.put(registryRevision, new RegistryChangeEvent(
                registryRevision, eventType, key, instance));
        while (changeLog.size() > options.changeLogCapacity()) {
            Map.Entry<Long, RegistryChangeEvent> removed = changeLog.pollFirstEntry();
            compactionRevision = removed.getKey();
        }
    }

    private OperationRecord success(
            String requestHash,
            String action,
            List<RegistryInstance> changed,
            List<InstanceKey> removed) {
        return success(requestHash, action, List.of(), changed, removed);
    }

    private OperationRecord success(
            String requestHash,
            String action,
            List<ServiceLifecycle> services,
            List<RegistryInstance> changed,
            List<InstanceKey> removed) {
        RegistryMutationResult result = new RegistryMutationResult(
                action,
                registryRevision,
                logicalTimeEpochMs,
                services,
                changed,
                removed);
        return new OperationRecord(
                requestHash,
                ApplyStatus.APPLIED,
                RegistryStateCodec.RESULT_MUTATION,
                RegistryStateCodec.encodeMutationResult(result),
                List.of(StateRevision.registry(groupId, registryRevision)));
    }

    private OperationRecord serviceSuccess(
            String requestHash, String action, ServiceLifecycle lifecycle) {
        RegistryMutationResult result = new RegistryMutationResult(
                action,
                registryRevision,
                logicalTimeEpochMs,
                List.of(lifecycle),
                List.of(),
                List.of());
        return new OperationRecord(
                requestHash,
                ApplyStatus.APPLIED,
                RegistryStateCodec.RESULT_MUTATION,
                RegistryStateCodec.encodeMutationResult(result),
                List.of(StateRevision.registry(groupId, registryRevision)));
    }

    private ApplyResult rejected(String operationId, String code, String message) {
        return new ApplyResult(
                groupId,
                operationId,
                ApplyStatus.REJECTED,
                appliedIndex,
                RegistryStateCodec.RESULT_MUTATION_ERROR,
                RegistryStateCodec.encodeMutationError(
                        new RegistryMutationError(code, message)),
                List.of());
    }

    private OperationRecord operationError(
            String requestHash, String code, String message) {
        return new OperationRecord(
                requestHash,
                ApplyStatus.REJECTED,
                RegistryStateCodec.RESULT_MUTATION_ERROR,
                RegistryStateCodec.encodeMutationError(
                        new RegistryMutationError(code, message)),
                List.of());
    }

    @Override
    public int snapshotSchemaVersion() {
        return SNAPSHOT_SCHEMA_VERSION;
    }

    @Override
    public StateMachineCompatibility compatibility() {
        return StateMachineCompatibility.exact(
                RegistryStateCodec.SCHEMA_VERSION, SNAPSHOT_SCHEMA_VERSION);
    }

    @Override
    public void writeSnapshot(OutputStream output) throws IOException {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        stateLock.readLock().lock();
        try {
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeLong(appliedIndex);
            data.writeLong(logicalTimeEpochMs);
            data.writeLong(registryRevision);
            data.writeLong(compactionRevision);
            data.writeInt(services.size());
            for (ServiceLifecycle lifecycle : services.values()) {
                RegistryStateCodec.writeServiceLifecycle(data, lifecycle);
            }
            data.writeInt(instances.size());
            for (RegistryInstance instance : instances.values()) {
                RegistryStateCodec.writeInstance(data, instance);
            }
            data.writeInt(leaseEpochs.size());
            for (Map.Entry<InstanceKey, Long> entry : leaseEpochs.entrySet()) {
                RegistryStateCodec.writeInstanceKey(data, entry.getKey());
                data.writeLong(entry.getValue());
            }
            data.writeInt(changeLog.size());
            for (RegistryChangeEvent event : changeLog.values()) {
                RegistryStateCodec.writeChangeEvent(data, event);
            }
            data.writeInt(operations.size());
            for (Map.Entry<OperationKey, OperationRecord> entry : operations.entrySet()) {
                RegistryStateCodec.writeString(data, entry.getKey().actorScope());
                RegistryStateCodec.writeString(data, entry.getKey().operationId());
                writeOperationRecord(data, entry.getValue());
            }
            data.flush();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void installSnapshot(int schemaVersion, InputStream input) throws IOException {
        if (schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
            throw new IOException(
                    "Unsupported Registry State snapshot schema: " + schemaVersion);
        }
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != SNAPSHOT_MAGIC) {
            throw new IOException("Invalid Registry State snapshot magic");
        }
        long restoredAppliedIndex = data.readLong();
        long restoredLogicalTime = data.readLong();
        long restoredRegistryRevision = data.readLong();
        long restoredCompactionRevision = data.readLong();
        if (restoredAppliedIndex < 0 || restoredLogicalTime < 0
                || restoredRegistryRevision < 0 || restoredCompactionRevision < 0
                || restoredCompactionRevision > restoredRegistryRevision) {
            throw new IOException("Invalid Registry State snapshot watermarks");
        }

        NavigableMap<ServiceKey, ServiceLifecycle> restoredServices = new TreeMap<>();
        int serviceCount = RegistryStateCodec.readSize(data);
        if (serviceCount > options.maxServices()) {
            throw new IOException("Snapshot exceeds Registry service capacity");
        }
        for (int i = 0; i < serviceCount; i++) {
            ServiceLifecycle lifecycle = RegistryStateCodec.readServiceLifecycle(data);
            if (restoredServices.put(lifecycle.serviceKey(), lifecycle) != null) {
                throw new IOException("Duplicate service lifecycle in snapshot");
            }
        }
        NavigableMap<InstanceKey, RegistryInstance> restoredInstances = new TreeMap<>();
        int instanceCount = RegistryStateCodec.readSize(data);
        if (instanceCount > options.maxInstances()) {
            throw new IOException("Snapshot exceeds Registry instance capacity");
        }
        for (int i = 0; i < instanceCount; i++) {
            RegistryInstance instance = RegistryStateCodec.readInstance(data);
            if (restoredInstances.put(instance.instanceKey(), instance) != null) {
                throw new IOException("Duplicate Registry instance in snapshot");
            }
        }
        NavigableMap<InstanceKey, Long> restoredEpochs = new TreeMap<>();
        int epochCount = RegistryStateCodec.readSize(data);
        for (int i = 0; i < epochCount; i++) {
            InstanceKey key = RegistryStateCodec.readInstanceKey(data);
            long epoch = data.readLong();
            if (epoch < 1 || restoredEpochs.put(key, epoch) != null) {
                throw new IOException("Invalid lease epoch history in snapshot");
            }
        }
        NavigableMap<Long, RegistryChangeEvent> restoredChangeLog = new TreeMap<>();
        int eventCount = RegistryStateCodec.readSize(data);
        if (eventCount > options.changeLogCapacity()) {
            throw new IOException("Snapshot ChangeLog exceeds configured capacity");
        }
        for (int i = 0; i < eventCount; i++) {
            RegistryChangeEvent event = RegistryStateCodec.readChangeEvent(data);
            if (restoredChangeLog.put(event.registryRevision(), event) != null) {
                throw new IOException("Duplicate Registry ChangeLog revision in snapshot");
            }
        }
        Map<OperationKey, OperationRecord> restoredOperations = new LinkedHashMap<>();
        int operationCount = RegistryStateCodec.readSize(data);
        if (operationCount > options.maxOperationRecords()) {
            throw new IOException("Snapshot operation history exceeds configured capacity");
        }
        for (int i = 0; i < operationCount; i++) {
            OperationKey key = new OperationKey(
                    RegistryStateCodec.readString(data),
                    RegistryStateCodec.readString(data));
            if (restoredOperations.put(key, readOperationRecord(data)) != null) {
                throw new IOException("Duplicate operation record in snapshot");
            }
        }
        if (data.read() != -1) {
            throw new IOException("Registry State snapshot contains trailing bytes");
        }
        validateRestoredState(
                restoredRegistryRevision,
                restoredCompactionRevision,
                restoredLogicalTime,
                restoredServices,
                restoredInstances,
                restoredEpochs,
                restoredChangeLog,
                restoredOperations);

        stateLock.writeLock().lock();
        try {
            services.clear();
            services.putAll(restoredServices);
            instances.clear();
            instances.putAll(restoredInstances);
            leaseEpochs.clear();
            leaseEpochs.putAll(restoredEpochs);
            changeLog.clear();
            changeLog.putAll(restoredChangeLog);
            operations.clear();
            operations.putAll(restoredOperations);
            appliedIndex = restoredAppliedIndex;
            logicalTimeEpochMs = restoredLogicalTime;
            registryRevision = restoredRegistryRevision;
            compactionRevision = restoredCompactionRevision;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private void validateRestoredState(
            long restoredRevision,
            long restoredCompaction,
            long restoredLogicalTime,
            NavigableMap<ServiceKey, ServiceLifecycle> restoredServices,
            NavigableMap<InstanceKey, RegistryInstance> restoredInstances,
            NavigableMap<InstanceKey, Long> restoredEpochs,
            NavigableMap<Long, RegistryChangeEvent> restoredChangeLog,
            Map<OperationKey, OperationRecord> restoredOperations) throws IOException {
        for (ServiceLifecycle lifecycle : restoredServices.values()) {
            if (lifecycle.updatedAtEpochMs() > restoredLogicalTime) {
                throw new IOException(
                        "Snapshot service lifecycle is ahead of logical time");
            }
        }
        for (RegistryInstance instance : restoredInstances.values()) {
            ServiceLifecycle lifecycle = restoredServices.get(
                    instance.instanceKey().service());
            if (lifecycle == null
                    || lifecycle.generation()
                    != instance.registration().serviceGeneration()) {
                throw new IOException(
                        "Snapshot instance has no matching service generation");
            }
            if (!lifecycle.active() && !instance.expiredAt(restoredLogicalTime)) {
                throw new IOException(
                        "Snapshot deleted service still has an active lease");
            }
            Long epoch = restoredEpochs.get(instance.instanceKey());
            if (epoch == null || epoch < instance.leaseEpoch()) {
                throw new IOException("Snapshot lease epoch history is incomplete");
            }
        }
        if (!restoredChangeLog.isEmpty()) {
            if (restoredChangeLog.firstKey() <= restoredCompaction
                    || restoredChangeLog.lastKey() > restoredRevision) {
                throw new IOException("Snapshot ChangeLog watermarks are inconsistent");
            }
        }
        long expected = restoredCompaction + 1;
        for (long revision : restoredChangeLog.keySet()) {
            if (revision != expected++) {
                throw new IOException("Snapshot ChangeLog contains a revision gap");
            }
        }
        if (restoredRevision > restoredCompaction
                && (restoredChangeLog.isEmpty()
                || restoredChangeLog.lastKey() != restoredRevision)) {
            throw new IOException("Snapshot ChangeLog does not reach its high watermark");
        }
        for (OperationRecord record : restoredOperations.values()) {
            for (StateRevision revision : record.revisions()) {
                if (!groupId.equals(revision.groupId())) {
                    throw new IOException("Snapshot operation belongs to another State Group");
                }
            }
        }
    }

    private static void writeOperationRecord(
            DataOutputStream data, OperationRecord value) throws IOException {
        RegistryStateCodec.writeString(data, value.requestHash());
        RegistryStateCodec.writeString(data, value.status().name());
        RegistryStateCodec.writeString(data, value.resultType());
        RegistryStateCodec.writeBytes(data, value.payload());
        RegistryStateCodec.writeList(
                data, value.revisions(), RegistryStateCodec::writeRevision);
    }

    private static OperationRecord readOperationRecord(DataInputStream data)
            throws IOException {
        return new OperationRecord(
                RegistryStateCodec.readString(data),
                ApplyStatus.valueOf(RegistryStateCodec.readString(data)),
                RegistryStateCodec.readString(data),
                RegistryStateCodec.readBytes(data),
                RegistryStateCodec.readList(data, RegistryStateCodec::readRevision));
    }

    private void requireGroup(StateGroupId target) {
        if (!groupId.equals(target)) {
            throw new IllegalArgumentException(
                    "Registry State message targets another State Group: " + target);
        }
    }

    private static void requireSchema(int schemaVersion) {
        if (schemaVersion != RegistryStateCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Registry State schema version: " + schemaVersion);
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private static MutationRejected reject(String code, String message) {
        return new MutationRejected(code, message);
    }

    private void compactOperationHistory() {
        while (operations.size() >= options.operationReplayWindow()) {
            var iterator = operations.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private record OperationKey(String actorScope, String operationId) {
        private OperationKey {
            if (actorScope == null || actorScope.isBlank()
                    || operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException(
                        "actorScope and operationId must not be blank");
            }
        }
    }

    private record OperationRecord(
            String requestHash,
            ApplyStatus status,
            String resultType,
            byte[] payload,
            List<StateRevision> revisions) {

        private OperationRecord {
            if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestHash must be SHA-256 hex");
            }
            if (status == null || resultType == null || resultType.isBlank()) {
                throw new IllegalArgumentException(
                        "operation status and resultType must be present");
            }
            payload = payload == null ? new byte[0] : payload.clone();
            revisions = List.copyOf(revisions == null ? List.of() : revisions);
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        private ApplyResult toApplyResult(
                StateGroupId groupId,
                String operationId,
                long appliedIndex,
                ApplyStatus returnedStatus) {
            return new ApplyResult(
                    groupId,
                    operationId,
                    returnedStatus,
                    appliedIndex,
                    resultType,
                    payload,
                    revisions);
        }

        private ResolvedRegistryOperation resolved() {
            return new ResolvedRegistryOperation(
                    true, requestHash, status, resultType, payload, revisions);
        }
    }

    private static final class MutationRejected extends RuntimeException {
        private final String code;

        private MutationRejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
