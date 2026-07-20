package cloud.xuantong.registry.state;

import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.StateRevisionType;
import cloud.xuantong.state.api.WatchRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class RegistryStateCodec {
    public static final int SCHEMA_VERSION = 2;

    public static final String COMMAND_MUTATE = "registry.mutate";
    public static final String QUERY_SNAPSHOT = "registry.snapshot";
    public static final String QUERY_LEASE_STATE = "registry.lease-state";
    public static final String QUERY_SERVICE_LIFECYCLE = "registry.service-lifecycle";
    public static final String QUERY_SERVICE_LIFECYCLE_SNAPSHOT =
            "registry.service-lifecycle-snapshot";
    public static final String QUERY_RESOLVE_OPERATION = "registry.resolve-operation";
    public static final String QUERY_OVERVIEW = "registry.overview";
    public static final String WATCH_CHANGES = "registry.changes";

    public static final String RESULT_MUTATION = "registry.mutation-result";
    public static final String RESULT_MUTATION_ERROR = "registry.mutation-error";
    public static final String RESULT_SNAPSHOT = "registry.snapshot";
    public static final String RESULT_LEASE_STATE = "registry.lease-state";
    public static final String RESULT_SERVICE_LIFECYCLE = "registry.service-lifecycle";
    public static final String RESULT_SERVICE_LIFECYCLE_SNAPSHOT =
            "registry.service-lifecycle-snapshot";
    public static final String RESULT_RESOLVED_OPERATION = "registry.resolved-operation";
    public static final String RESULT_OVERVIEW = "registry.overview";
    public static final String EVENT_INSTANCE_CHANGED = "registry.instance-changed";

    private static final int MAGIC = 0x58524732;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LIST = 1_000_000;

    private RegistryStateCodec() {
    }

    public static StateCommand mutationCommand(
            StateGroupId groupId, String operationId, RegistryMutation mutation) {
        requireRegistryGroup(groupId);
        return new StateCommand(
                groupId,
                operationId,
                COMMAND_MUTATE,
                SCHEMA_VERSION,
                encodeMutation(mutation));
    }

    public static StateQuery snapshotQuery(
            StateGroupId groupId,
            RegistrySnapshotRequest request,
            ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_SNAPSHOT,
                SCHEMA_VERSION,
                encodeSnapshotRequest(request),
                readOptions);
    }

    public static StateQuery leaseStateQuery(
            StateGroupId groupId,
            GetLeaseStateRequest request,
            ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_LEASE_STATE,
                SCHEMA_VERSION,
                encodeLeaseStateRequest(request),
                readOptions);
    }

    public static StateQuery serviceLifecycleQuery(
            StateGroupId groupId,
            GetServiceLifecycleRequest request,
            ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_SERVICE_LIFECYCLE,
                SCHEMA_VERSION,
                encodeServiceLifecycleRequest(request),
                readOptions);
    }

    public static StateQuery serviceLifecycleSnapshotQuery(
            StateGroupId groupId, ReadOptions readOptions) {
        return serviceLifecycleSnapshotQuery(
                groupId, new ServiceLifecycleSnapshotRequest(null, 100), readOptions);
    }

    public static StateQuery serviceLifecycleSnapshotQuery(
            StateGroupId groupId,
            ServiceLifecycleSnapshotRequest request,
            ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_SERVICE_LIFECYCLE_SNAPSHOT,
                SCHEMA_VERSION,
                encodeServiceLifecycleSnapshotRequest(request),
                readOptions);
    }

    public static StateQuery resolveOperationQuery(
            StateGroupId groupId,
            ResolveRegistryOperationRequest request,
            ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_RESOLVE_OPERATION,
                SCHEMA_VERSION,
                encodeResolveOperationRequest(request),
                readOptions);
    }

    public static StateQuery overviewQuery(
            StateGroupId groupId, ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_OVERVIEW,
                SCHEMA_VERSION,
                new byte[0],
                readOptions);
    }

    public static WatchRequest changesWatch(
            StateGroupId groupId,
            long afterRevision,
            RegistrySnapshotRequest selector,
            int maxBatchSize,
            ReadOptions readOptions) {
        requireRegistryGroup(groupId);
        return new WatchRequest(
                StateRevision.registry(groupId, afterRevision),
                WATCH_CHANGES,
                SCHEMA_VERSION,
                encodeSnapshotRequest(selector),
                maxBatchSize,
                readOptions);
    }

    public static String requestHash(StateCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(command.groupId().canonicalName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(command.commandType().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(command.schemaVersion())
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(semanticPayload(command));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] semanticPayload(StateCommand command) {
        if (!COMMAND_MUTATE.equals(command.commandType())) {
            return command.payload();
        }
        try {
            RegistryMutation mutation = decodeMutation(command.payload());
            RegistryMutation normalized = switch (mutation) {
                case RegisterLease value -> new RegisterLease(
                        value.actor(), value.registration(), value.proposedLeaseId(),
                        value.ttlMs(), 0L);
                case RenewLeaseBatch value -> new RenewLeaseBatch(
                        value.actor(), value.renewals(), 0L);
                case DeregisterLease value -> new DeregisterLease(
                        value.actor(), value.lease(), 0L);
                case TakeoverLease value -> new TakeoverLease(
                        value.actor(), value.expectedLease(), value.proposedLeaseId(),
                        value.ttlMs(), 0L);
                case ExpireLeaseBatch value -> value;
                case EvictLease value -> new EvictLease(
                        value.actor(), value.expectedLease(), 0L);
                case ActivateServiceDefinition value -> new ActivateServiceDefinition(
                        value.actor(), value.serviceKey(),
                        value.expectedPreviousGeneration(), 0L);
                case DeleteServiceDefinition value -> new DeleteServiceDefinition(
                        value.actor(), value.serviceKey(), value.expectedGeneration(), 0L);
            };
            return encodeMutation(normalized);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed Registry mutation", e);
        }
    }

    public static byte[] encodeMutation(RegistryMutation value) {
        return encode(data -> {
            data.writeInt(MAGIC);
            if (value instanceof RegisterLease register) {
                data.writeByte(1);
                writeActor(data, register.actor());
                writeRegistration(data, register.registration());
                writeString(data, register.proposedLeaseId());
                data.writeLong(register.ttlMs());
                data.writeLong(register.observedTimeEpochMs());
            } else if (value instanceof RenewLeaseBatch renew) {
                data.writeByte(2);
                writeActor(data, renew.actor());
                writeList(data, renew.renewals(), RegistryStateCodec::writeRenewal);
                data.writeLong(renew.observedTimeEpochMs());
            } else if (value instanceof DeregisterLease deregister) {
                data.writeByte(3);
                writeActor(data, deregister.actor());
                writeLeaseReference(data, deregister.lease());
                data.writeLong(deregister.observedTimeEpochMs());
            } else if (value instanceof TakeoverLease takeover) {
                data.writeByte(4);
                writeActor(data, takeover.actor());
                writeLeaseReference(data, takeover.expectedLease());
                writeString(data, takeover.proposedLeaseId());
                data.writeLong(takeover.ttlMs());
                data.writeLong(takeover.observedTimeEpochMs());
            } else if (value instanceof ExpireLeaseBatch expire) {
                data.writeByte(5);
                writeActor(data, expire.actor());
                data.writeInt(expire.maxExpirations());
                data.writeLong(expire.observedTimeEpochMs());
            } else if (value instanceof EvictLease evict) {
                data.writeByte(6);
                writeActor(data, evict.actor());
                writeLeaseReference(data, evict.expectedLease());
                data.writeLong(evict.observedTimeEpochMs());
            } else if (value instanceof ActivateServiceDefinition activate) {
                data.writeByte(7);
                writeActor(data, activate.actor());
                writeServiceKey(data, activate.serviceKey());
                data.writeLong(activate.expectedPreviousGeneration());
                data.writeLong(activate.observedTimeEpochMs());
            } else if (value instanceof DeleteServiceDefinition delete) {
                data.writeByte(8);
                writeActor(data, delete.actor());
                writeServiceKey(data, delete.serviceKey());
                data.writeLong(delete.expectedGeneration());
                data.writeLong(delete.observedTimeEpochMs());
            } else {
                throw new IllegalArgumentException("Unsupported Registry mutation");
            }
        });
    }

    public static RegistryMutation decodeMutation(byte[] bytes) throws IOException {
        return decode(bytes, data -> {
            requireMagic(data);
            return switch (data.readUnsignedByte()) {
                case 1 -> new RegisterLease(
                        readActor(data),
                        readRegistration(data),
                        readString(data),
                        data.readLong(),
                        data.readLong());
                case 2 -> new RenewLeaseBatch(
                        readActor(data),
                        readList(data, RegistryStateCodec::readRenewal),
                        data.readLong());
                case 3 -> new DeregisterLease(
                        readActor(data), readLeaseReference(data), data.readLong());
                case 4 -> new TakeoverLease(
                        readActor(data),
                        readLeaseReference(data),
                        readString(data),
                        data.readLong(),
                        data.readLong());
                case 5 -> new ExpireLeaseBatch(
                        readActor(data), data.readInt(), data.readLong());
                case 6 -> new EvictLease(
                        readActor(data), readLeaseReference(data), data.readLong());
                case 7 -> new ActivateServiceDefinition(
                        readActor(data), readServiceKey(data),
                        data.readLong(), data.readLong());
                case 8 -> new DeleteServiceDefinition(
                        readActor(data), readServiceKey(data),
                        data.readLong(), data.readLong());
                default -> throw new IOException("Unknown Registry mutation type");
            };
        });
    }

    public static byte[] encodeMutationResult(RegistryMutationResult value) {
        return encode(data -> {
            writeString(data, value.action());
            data.writeLong(value.registryRevision());
            data.writeLong(value.serverTimeEpochMs());
            writeList(data, value.services(), RegistryStateCodec::writeServiceLifecycle);
            writeList(data, value.instances(), RegistryStateCodec::writeInstance);
            writeList(data, value.removedInstances(), RegistryStateCodec::writeInstanceKey);
        });
    }

    public static RegistryMutationResult decodeMutationResult(byte[] bytes) throws IOException {
        return decode(bytes, data -> new RegistryMutationResult(
                readString(data),
                data.readLong(),
                data.readLong(),
                readList(data, RegistryStateCodec::readServiceLifecycle),
                readList(data, RegistryStateCodec::readInstance),
                readList(data, RegistryStateCodec::readInstanceKey)));
    }

    public static byte[] encodeMutationError(RegistryMutationError value) {
        return encode(data -> {
            writeString(data, value.code());
            writeString(data, value.message());
        });
    }

    public static RegistryMutationError decodeMutationError(byte[] bytes) throws IOException {
        return decode(bytes, data -> new RegistryMutationError(
                readString(data), readString(data)));
    }

    public static byte[] encodeSnapshotRequest(RegistrySnapshotRequest value) {
        return encode(data -> {
            writeString(data, value.namespace());
            writeString(data, value.group());
            writeList(data, value.serviceNames(), RegistryStateCodec::writeString);
        });
    }

    public static RegistrySnapshotRequest decodeSnapshotRequest(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> new RegistrySnapshotRequest(
                readString(data),
                readString(data),
                readList(data, RegistryStateCodec::readString)));
    }

    public static byte[] encodeSnapshot(RegistrySnapshot value) {
        return encode(data -> {
            data.writeLong(value.registryRevision());
            data.writeLong(value.compactionRevision());
            data.writeLong(value.serverTimeEpochMs());
            writeList(data, value.instances(), RegistryStateCodec::writeInstance);
        });
    }

    public static RegistrySnapshot decodeSnapshot(byte[] bytes) throws IOException {
        return decode(bytes, data -> new RegistrySnapshot(
                data.readLong(),
                data.readLong(),
                data.readLong(),
                readList(data, RegistryStateCodec::readInstance)));
    }

    public static byte[] encodeChangeEvent(RegistryChangeEvent value) {
        return encode(data -> writeChangeEvent(data, value));
    }

    public static RegistryChangeEvent decodeChangeEvent(byte[] bytes) throws IOException {
        return decode(bytes, RegistryStateCodec::readChangeEvent);
    }

    public static byte[] encodeLeaseStateRequest(GetLeaseStateRequest value) {
        return encode(data -> writeInstanceKey(data, value.instanceKey()));
    }

    public static GetLeaseStateRequest decodeLeaseStateRequest(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> new GetLeaseStateRequest(readInstanceKey(data)));
    }

    public static byte[] encodeLeaseState(LeaseState value) {
        return encode(data -> {
            data.writeBoolean(value.found());
            data.writeLong(value.registryRevision());
            data.writeLong(value.serverTimeEpochMs());
            if (value.found()) {
                writeInstance(data, value.instance());
            }
        });
    }

    public static LeaseState decodeLeaseState(byte[] bytes) throws IOException {
        return decode(bytes, data -> {
            boolean found = data.readBoolean();
            long revision = data.readLong();
            long serverTime = data.readLong();
            return new LeaseState(
                    found, revision, serverTime, found ? readInstance(data) : null);
        });
    }

    public static byte[] encodeServiceLifecycleRequest(
            GetServiceLifecycleRequest value) {
        return encode(data -> writeServiceKey(data, value.serviceKey()));
    }

    public static GetServiceLifecycleRequest decodeServiceLifecycleRequest(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> new GetServiceLifecycleRequest(readServiceKey(data)));
    }

    public static byte[] encodeServiceLifecycleState(ServiceLifecycleState value) {
        return encode(data -> {
            data.writeBoolean(value.found());
            data.writeLong(value.registryRevision());
            data.writeLong(value.serverTimeEpochMs());
            if (value.found()) {
                writeServiceLifecycle(data, value.lifecycle());
            }
        });
    }

    public static ServiceLifecycleState decodeServiceLifecycleState(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> {
            boolean found = data.readBoolean();
            long revision = data.readLong();
            long serverTime = data.readLong();
            return new ServiceLifecycleState(
                    found,
                    revision,
                    serverTime,
                    found ? readServiceLifecycle(data) : null);
        });
    }

    public static byte[] encodeServiceLifecycleSnapshot(
            ServiceLifecycleSnapshot value) {
        return encode(data -> {
            data.writeLong(value.registryRevision());
            data.writeLong(value.serverTimeEpochMs());
            writeList(data, value.services(), RegistryStateCodec::writeServiceLifecycle);
            data.writeBoolean(value.hasMore());
        });
    }

    public static ServiceLifecycleSnapshot decodeServiceLifecycleSnapshot(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> new ServiceLifecycleSnapshot(
                data.readLong(),
                data.readLong(),
                readList(data, RegistryStateCodec::readServiceLifecycle),
                data.readBoolean()));
    }

    public static byte[] encodeServiceLifecycleSnapshotRequest(
            ServiceLifecycleSnapshotRequest value) {
        return encode(data -> {
            data.writeBoolean(value.afterExclusive() != null);
            if (value.afterExclusive() != null) {
                writeServiceKey(data, value.afterExclusive());
            }
            data.writeInt(value.limit());
        });
    }

    public static ServiceLifecycleSnapshotRequest decodeServiceLifecycleSnapshotRequest(
            byte[] bytes) throws IOException {
        return decode(bytes, data -> new ServiceLifecycleSnapshotRequest(
                data.readBoolean() ? readServiceKey(data) : null,
                data.readInt()));
    }

    public static byte[] encodeResolveOperationRequest(
            ResolveRegistryOperationRequest value) {
        return encode(data -> {
            writeActor(data, value.actor());
            writeString(data, value.operationId());
        });
    }

    public static ResolveRegistryOperationRequest decodeResolveOperationRequest(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> new ResolveRegistryOperationRequest(
                readActor(data), readString(data)));
    }

    public static byte[] encodeResolvedOperation(ResolvedRegistryOperation value) {
        return encode(data -> {
            data.writeBoolean(value.found());
            if (!value.found()) {
                return;
            }
            writeString(data, value.requestHash());
            writeString(data, value.status().name());
            writeString(data, value.resultType());
            writeBytes(data, value.payload());
            writeList(data, value.revisions(), RegistryStateCodec::writeRevision);
        });
    }

    public static ResolvedRegistryOperation decodeResolvedOperation(byte[] bytes)
            throws IOException {
        return decode(bytes, data -> {
            if (!data.readBoolean()) {
                return ResolvedRegistryOperation.missing();
            }
            return new ResolvedRegistryOperation(
                    true,
                    readString(data),
                    ApplyStatus.valueOf(readString(data)),
                    readString(data),
                    readBytes(data),
                    readList(data, RegistryStateCodec::readRevision));
        });
    }

    public static byte[] encodeOverview(RegistryOverview value) {
        return encode(data -> {
            data.writeLong(value.registryRevision());
            data.writeLong(value.serverTimeEpochMs());
            data.writeLong(value.activeInstanceCount());
            data.writeLong(value.activeServiceCount());
        });
    }

    public static RegistryOverview decodeOverview(byte[] bytes) throws IOException {
        return decode(bytes, data -> new RegistryOverview(
                data.readLong(), data.readLong(), data.readLong(), data.readLong()));
    }

    static void writeActor(DataOutputStream data, RegistryActor value) throws IOException {
        writeString(data, value.tenant());
        writeString(data, value.clientInstanceId());
        writeString(data, value.applicationName());
    }

    static RegistryActor readActor(DataInputStream data) throws IOException {
        return new RegistryActor(readString(data), readString(data), readString(data));
    }

    static void writeServiceKey(DataOutputStream data, ServiceKey value) throws IOException {
        writeString(data, value.namespace());
        writeString(data, value.group());
        writeString(data, value.serviceName());
    }

    static ServiceKey readServiceKey(DataInputStream data) throws IOException {
        return new ServiceKey(readString(data), readString(data), readString(data));
    }

    static void writeInstanceKey(DataOutputStream data, InstanceKey value) throws IOException {
        writeServiceKey(data, value.service());
        writeString(data, value.instanceId());
    }

    static InstanceKey readInstanceKey(DataInputStream data) throws IOException {
        return new InstanceKey(readServiceKey(data), readString(data));
    }

    static void writeRegistration(DataOutputStream data, ServiceRegistration value)
            throws IOException {
        writeInstanceKey(data, value.instanceKey());
        data.writeLong(value.serviceGeneration());
        writeString(data, value.ip());
        data.writeInt(value.port());
        data.writeDouble(value.weight());
        data.writeBoolean(value.enabled());
        writeString(data, value.metadata());
    }

    static ServiceRegistration readRegistration(DataInputStream data) throws IOException {
        return new ServiceRegistration(
                readInstanceKey(data),
                data.readLong(),
                readString(data),
                data.readInt(),
                data.readDouble(),
                data.readBoolean(),
                readString(data));
    }

    static void writeServiceLifecycle(DataOutputStream data, ServiceLifecycle value)
            throws IOException {
        writeServiceKey(data, value.serviceKey());
        data.writeLong(value.generation());
        writeString(data, value.status().name());
        data.writeLong(value.updatedAtEpochMs());
    }

    static ServiceLifecycle readServiceLifecycle(DataInputStream data) throws IOException {
        return new ServiceLifecycle(
                readServiceKey(data),
                data.readLong(),
                ServiceLifecycleStatus.valueOf(readString(data)),
                data.readLong());
    }

    static void writeLeaseReference(DataOutputStream data, LeaseReference value)
            throws IOException {
        writeInstanceKey(data, value.instanceKey());
        writeString(data, value.leaseId());
        data.writeLong(value.leaseEpoch());
        data.writeLong(value.recoveryEpoch());
    }

    static LeaseReference readLeaseReference(DataInputStream data) throws IOException {
        return new LeaseReference(
                readInstanceKey(data),
                readString(data),
                data.readLong(),
                data.readLong());
    }

    static void writeRenewal(DataOutputStream data, LeaseRenewal value) throws IOException {
        writeLeaseReference(data, value.lease());
        data.writeLong(value.renewSequence());
        data.writeLong(value.ttlMs());
    }

    static LeaseRenewal readRenewal(DataInputStream data) throws IOException {
        return new LeaseRenewal(
                readLeaseReference(data), data.readLong(), data.readLong());
    }

    static void writeInstance(DataOutputStream data, RegistryInstance value)
            throws IOException {
        writeRegistration(data, value.registration());
        writeString(data, value.leaseId());
        data.writeLong(value.leaseEpoch());
        data.writeLong(value.recoveryEpoch());
        data.writeLong(value.renewSequence());
        writeString(data, value.ownerClientInstanceId());
        writeString(data, value.ownerApplicationName());
        data.writeLong(value.registeredAtEpochMs());
        data.writeLong(value.lastRenewedAtEpochMs());
        data.writeLong(value.expiresAtEpochMs());
    }

    static RegistryInstance readInstance(DataInputStream data) throws IOException {
        return new RegistryInstance(
                readRegistration(data),
                readString(data),
                data.readLong(),
                data.readLong(),
                data.readLong(),
                readString(data),
                readString(data),
                data.readLong(),
                data.readLong(),
                data.readLong());
    }

    static void writeChangeEvent(DataOutputStream data, RegistryChangeEvent value)
            throws IOException {
        data.writeLong(value.registryRevision());
        writeString(data, value.eventType());
        writeInstanceKey(data, value.instanceKey());
        data.writeBoolean(value.instance() != null);
        if (value.instance() != null) {
            writeInstance(data, value.instance());
        }
    }

    static RegistryChangeEvent readChangeEvent(DataInputStream data) throws IOException {
        long revision = data.readLong();
        String eventType = readString(data);
        InstanceKey key = readInstanceKey(data);
        RegistryInstance instance = data.readBoolean() ? readInstance(data) : null;
        return new RegistryChangeEvent(revision, eventType, key, instance);
    }

    static void writeRevision(DataOutputStream data, StateRevision value) throws IOException {
        writeString(data, value.groupId().type().name());
        writeString(data, value.groupId().value());
        writeString(data, value.type().name());
        writeString(data, value.scope());
        data.writeLong(value.value());
    }

    static StateRevision readRevision(DataInputStream data) throws IOException {
        StateGroupId groupId = new StateGroupId(
                StateGroupType.valueOf(readString(data)), readString(data));
        return new StateRevision(
                groupId,
                StateRevisionType.valueOf(readString(data)),
                readString(data),
                data.readLong());
    }

    static void writeString(DataOutputStream data, String value) throws IOException {
        writeBytes(data, value.getBytes(StandardCharsets.UTF_8));
    }

    static String readString(DataInputStream data) throws IOException {
        return new String(readBytes(data, MAX_STRING_BYTES), StandardCharsets.UTF_8);
    }

    static void writeBytes(DataOutputStream data, byte[] value) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value;
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Encoded value is too large");
        }
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    static byte[] readBytes(DataInputStream data) throws IOException {
        return readBytes(data, MAX_BYTES);
    }

    static int readSize(DataInputStream data) throws IOException {
        int size = data.readInt();
        if (size < 0 || size > MAX_LIST) {
            throw new IOException("Invalid collection size: " + size);
        }
        return size;
    }

    static <T> void writeList(
            DataOutputStream data, List<T> values, Writer<T> writer) throws IOException {
        if (values.size() > MAX_LIST) {
            throw new IOException("Collection is too large");
        }
        data.writeInt(values.size());
        for (T value : values) {
            writer.write(data, value);
        }
    }

    static <T> List<T> readList(DataInputStream data, Reader<T> reader) throws IOException {
        int size = readSize(data);
        List<T> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(reader.read(data));
        }
        return List.copyOf(values);
    }

    private static byte[] readBytes(DataInputStream data, int maxBytes) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > maxBytes) {
            throw new IOException("Invalid byte length: " + length);
        }
        byte[] value = data.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Unexpected end of Registry State byte value");
        }
        return value;
    }

    private static byte[] encode(Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            encoder.write(data);
            data.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode Registry State value", e);
        }
    }

    private static <T> T decode(byte[] bytes, Decoder<T> decoder) throws IOException {
        if (bytes == null) {
            throw new IOException("Encoded Registry State value must not be null");
        }
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            T value = decoder.read(data);
            if (data.available() != 0) {
                throw new IOException("Encoded Registry State value contains trailing bytes");
            }
            return value;
        } catch (EOFException e) {
            throw new IOException("Encoded Registry State value is truncated", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Encoded Registry State value is invalid", e);
        }
    }

    private static void requireMagic(DataInputStream data) throws IOException {
        if (data.readInt() != MAGIC) {
            throw new IOException("Invalid Registry State payload magic");
        }
    }

    private static void requireRegistryGroup(StateGroupId groupId) {
        if (groupId == null || groupId.type() != StateGroupType.REGISTRY) {
            throw new IllegalArgumentException("Registry State requires a REGISTRY group");
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream data) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T read(DataInputStream data) throws IOException;
    }

    @FunctionalInterface
    interface Writer<T> {
        void write(DataOutputStream data, T value) throws IOException;
    }

    @FunctionalInterface
    interface Reader<T> {
        T read(DataInputStream data) throws IOException;
    }
}
