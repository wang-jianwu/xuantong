package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadConsistency;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.StateRevisionType;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchEvent;
import cloud.xuantong.state.api.WatchRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class RatisStateMessageCodec {
    private static final int MAGIC = 0x58545332;
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LIST_SIZE = 100_000;

    static final int COMMAND = 1;
    static final int QUERY = 2;
    static final int WATCH_REQUEST = 3;
    private static final int APPLY_RESULT = 11;
    private static final int QUERY_RESULT = 12;
    private static final int WATCH_BATCH = 13;

    private RatisStateMessageCodec() {
    }

    static byte[] encodeCommand(StateCommand command) throws IOException {
        return encode(COMMAND, output -> {
            writeGroup(output, command.groupId());
            writeString(output, command.operationId());
            writeString(output, command.commandType());
            output.writeInt(command.schemaVersion());
            writeBytes(output, command.payload());
        });
    }

    static StateCommand decodeCommand(byte[] bytes) throws IOException {
        try (DataInputStream input = open(bytes, COMMAND)) {
            StateCommand value = new StateCommand(
                    readGroup(input),
                    readString(input),
                    readString(input),
                    input.readInt(),
                    readBytes(input));
            requireExhausted(input);
            return value;
        }
    }

    static byte[] encodeQuery(StateQuery query) throws IOException {
        return encode(QUERY, output -> {
            writeGroup(output, query.groupId());
            writeString(output, query.queryType());
            output.writeInt(query.schemaVersion());
            writeBytes(output, query.payload());
            writeReadOptions(output, query.readOptions());
        });
    }

    static StateQuery decodeQuery(byte[] bytes) throws IOException {
        try (DataInputStream input = open(bytes, QUERY)) {
            StateQuery value = new StateQuery(
                    readGroup(input),
                    readString(input),
                    input.readInt(),
                    readBytes(input),
                    readReadOptions(input));
            requireExhausted(input);
            return value;
        }
    }

    static byte[] encodeWatchRequest(WatchRequest request) throws IOException {
        return encode(WATCH_REQUEST, output -> {
            writeRevision(output, request.afterRevision());
            writeString(output, request.watchType());
            output.writeInt(request.schemaVersion());
            writeBytes(output, request.selector());
            output.writeInt(request.maxBatchSize());
            writeReadOptions(output, request.readOptions());
        });
    }

    static WatchRequest decodeWatchRequest(byte[] bytes) throws IOException {
        try (DataInputStream input = open(bytes, WATCH_REQUEST)) {
            WatchRequest value = new WatchRequest(
                    readRevision(input),
                    readString(input),
                    input.readInt(),
                    readBytes(input),
                    input.readInt(),
                    readReadOptions(input));
            requireExhausted(input);
            return value;
        }
    }

    static byte[] encodeApplyResult(ApplyResult result) throws IOException {
        return encode(APPLY_RESULT, output -> {
            writeGroup(output, result.groupId());
            writeString(output, result.operationId());
            writeEnum(output, result.status());
            output.writeLong(result.appliedIndex());
            writeString(output, result.resultType());
            writeBytes(output, result.payload());
            writeRevisions(output, result.revisions());
        });
    }

    static ApplyResult decodeApplyResult(byte[] bytes) throws IOException {
        try (DataInputStream input = open(bytes, APPLY_RESULT)) {
            ApplyResult value = new ApplyResult(
                    readGroup(input),
                    readString(input),
                    readEnum(input, ApplyStatus.class),
                    input.readLong(),
                    readString(input),
                    readBytes(input),
                    readRevisions(input));
            requireExhausted(input);
            return value;
        }
    }

    static byte[] encodeQueryResult(QueryResult result) throws IOException {
        return encode(QUERY_RESULT, output -> {
            writeGroup(output, result.groupId());
            output.writeLong(result.appliedIndex());
            output.writeBoolean(result.stale());
            writeString(output, result.resultType());
            writeBytes(output, result.payload());
            writeRevisions(output, result.revisions());
        });
    }

    static QueryResult decodeQueryResult(byte[] bytes) throws IOException {
        try (DataInputStream input = open(bytes, QUERY_RESULT)) {
            QueryResult value = new QueryResult(
                    readGroup(input),
                    input.readLong(),
                    input.readBoolean(),
                    readString(input),
                    readBytes(input),
                    readRevisions(input));
            requireExhausted(input);
            return value;
        }
    }

    static byte[] encodeWatchBatch(WatchBatch batch) throws IOException {
        return encode(WATCH_BATCH, output -> {
            writeRevision(output, batch.requestedAfter());
            writeRevision(output, batch.coveredThrough());
            writeRevision(output, batch.compactionRevision());
            output.writeBoolean(batch.resetRequired());
            writeSize(output, batch.events().size());
            for (WatchEvent event : batch.events()) {
                writeRevision(output, event.revision());
                writeString(output, event.eventType());
                output.writeInt(event.schemaVersion());
                writeBytes(output, event.payload());
            }
        });
    }

    static WatchBatch decodeWatchBatch(byte[] bytes) throws IOException {
        try (DataInputStream input = open(bytes, WATCH_BATCH)) {
            StateRevision requestedAfter = readRevision(input);
            StateRevision coveredThrough = readRevision(input);
            StateRevision compactionRevision = readRevision(input);
            boolean resetRequired = input.readBoolean();
            int size = readSize(input);
            List<WatchEvent> events = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                events.add(new WatchEvent(
                        readRevision(input),
                        readString(input),
                        input.readInt(),
                        readBytes(input)));
            }
            WatchBatch value = new WatchBatch(
                    requestedAfter,
                    coveredThrough,
                    compactionRevision,
                    resetRequired,
                    events);
            requireExhausted(input);
            return value;
        }
    }

    static int messageType(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(requireBytes(bytes)))) {
            requireHeader(input);
            return input.readUnsignedByte();
        }
    }

    private static void writeReadOptions(
            DataOutputStream output, ReadOptions options) throws IOException {
        writeEnum(output, options.consistency());
        output.writeBoolean(options.minimumRevision() != null);
        if (options.minimumRevision() != null) {
            writeRevision(output, options.minimumRevision());
        }
        output.writeLong(options.maxStaleness().getSeconds());
        output.writeInt(options.maxStaleness().getNano());
    }

    private static ReadOptions readReadOptions(DataInputStream input) throws IOException {
        ReadConsistency consistency = readEnum(input, ReadConsistency.class);
        StateRevision minimum = input.readBoolean() ? readRevision(input) : null;
        long seconds = input.readLong();
        int nanos = input.readInt();
        try {
            return new ReadOptions(
                    consistency, minimum, Duration.ofSeconds(seconds, nanos));
        } catch (RuntimeException e) {
            throw invalid("Invalid read options", e);
        }
    }

    private static void writeRevisions(
            DataOutputStream output, List<StateRevision> revisions) throws IOException {
        writeSize(output, revisions.size());
        for (StateRevision revision : revisions) {
            writeRevision(output, revision);
        }
    }

    private static List<StateRevision> readRevisions(DataInputStream input) throws IOException {
        int size = readSize(input);
        List<StateRevision> revisions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            revisions.add(readRevision(input));
        }
        return revisions;
    }

    private static void writeRevision(
            DataOutputStream output, StateRevision revision) throws IOException {
        writeGroup(output, revision.groupId());
        writeEnum(output, revision.type());
        writeString(output, revision.scope());
        output.writeLong(revision.value());
    }

    private static StateRevision readRevision(DataInputStream input) throws IOException {
        try {
            return new StateRevision(
                    readGroup(input),
                    readEnum(input, StateRevisionType.class),
                    readString(input),
                    input.readLong());
        } catch (RuntimeException e) {
            throw invalid("Invalid state revision", e);
        }
    }

    private static void writeGroup(
            DataOutputStream output, StateGroupId groupId) throws IOException {
        writeEnum(output, groupId.type());
        writeString(output, groupId.value());
    }

    private static StateGroupId readGroup(DataInputStream input) throws IOException {
        try {
            return new StateGroupId(
                    readEnum(input, StateGroupType.class), readString(input));
        } catch (RuntimeException e) {
            throw invalid("Invalid state group", e);
        }
    }

    private static <E extends Enum<E>> void writeEnum(
            DataOutputStream output, E value) throws IOException {
        writeString(output, value.name());
    }

    private static <E extends Enum<E>> E readEnum(
            DataInputStream input, Class<E> enumType) throws IOException {
        String name = readString(input);
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException e) {
            throw invalid("Unknown " + enumType.getSimpleName() + ": " + name, e);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String exceeds " + MAX_STRING_BYTES + " bytes");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid payload length: " + length);
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Unexpected end of payload");
        }
        return value;
    }

    private static void writeSize(DataOutputStream output, int size) throws IOException {
        if (size < 0 || size > MAX_LIST_SIZE) {
            throw new IOException("Invalid list size: " + size);
        }
        output.writeInt(size);
    }

    private static int readSize(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_LIST_SIZE) {
            throw new IOException("Invalid list size: " + size);
        }
        return size;
    }

    private static byte[] encode(int messageType, Encoder encoder) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(messageType);
            encoder.encode(output);
        }
        return buffer.toByteArray();
    }

    private static DataInputStream open(byte[] bytes, int expectedType) throws IOException {
        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(requireBytes(bytes)));
        requireHeader(input);
        int actualType = input.readUnsignedByte();
        if (actualType != expectedType) {
            throw new IOException("Unexpected state message type: " + actualType
                    + ", expected " + expectedType);
        }
        return input;
    }

    private static void requireHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) {
            throw new IOException("Invalid state message magic");
        }
        int version = input.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("Unsupported state message version: " + version);
        }
    }

    private static byte[] requireBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < Integer.BYTES + 2) {
            throw new IOException("State message is empty or truncated");
        }
        return bytes;
    }

    private static void requireExhausted(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IOException("State message contains trailing bytes");
        }
    }

    private static IOException invalid(String message, RuntimeException cause) {
        return new IOException(message, cause);
    }

    @FunctionalInterface
    private interface Encoder {
        void encode(DataOutputStream output) throws IOException;
    }
}
