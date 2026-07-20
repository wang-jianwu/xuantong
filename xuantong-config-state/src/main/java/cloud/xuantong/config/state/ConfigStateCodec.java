package cloud.xuantong.config.state;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned deterministic binary contract between Gateway and Config State. */
public final class ConfigStateCodec {
    public static final int SCHEMA_VERSION = 2;

    public static final String COMMAND_MUTATE = "config.mutate";
    public static final String QUERY_APPLICABLE_RELEASE = "config.applicable-release";
    public static final String QUERY_SNAPSHOT = "config.snapshot";
    public static final String QUERY_PROJECTION_SNAPSHOT = "config.projection-snapshot";
    public static final String QUERY_RESOLVE_OPERATION = "config.resolve-operation";
    public static final String WATCH_CHANGES = "config.changes";

    public static final String RESULT_MUTATION = "config.mutation-result";
    public static final String RESULT_MUTATION_ERROR = "config.mutation-error";
    public static final String RESULT_APPLICABLE_RELEASE = "config.applicable-release";
    public static final String RESULT_SNAPSHOT = "config.snapshot";
    public static final String RESULT_PROJECTION_SNAPSHOT = "config.projection-snapshot";
    public static final String RESULT_RESOLVED_OPERATION = "config.resolved-operation";
    public static final String EVENT_INVALIDATED = "config.invalidated";

    private static final int MAGIC = 0x58434632;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LIST = 100_000;

    private ConfigStateCodec() {
    }

    public static StateCommand mutationCommand(
            StateGroupId groupId, String operationId, ConfigMutation mutation) {
        requireConfigGroup(groupId);
        return new StateCommand(
                groupId, operationId, COMMAND_MUTATE, SCHEMA_VERSION, encodeMutation(mutation));
    }

    /** Stable hash used by both the Raft idempotency record and SQL recovery record. */
    public static String requestHash(StateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        digest.update(command.commandType().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Integer.toString(command.schemaVersion())
                .getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(command.payload());
        return HexFormat.of().formatHex(digest.digest());
    }

    public static StateQuery applicableReleaseQuery(
            StateGroupId groupId,
            ApplicableReleaseRequest request,
            ReadOptions readOptions) {
        requireConfigGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_APPLICABLE_RELEASE,
                SCHEMA_VERSION,
                encodeApplicableReleaseRequest(request),
                readOptions);
    }

    public static StateQuery snapshotQuery(
            StateGroupId groupId,
            ConfigSnapshotRequest request,
            ReadOptions readOptions) {
        requireConfigGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_SNAPSHOT,
                SCHEMA_VERSION,
                encodeSnapshotRequest(request),
                readOptions);
    }

    public static StateQuery projectionSnapshotQuery(
            StateGroupId groupId, ReadOptions readOptions) {
        return projectionSnapshotQuery(
                groupId, new ConfigProjectionSnapshotRequest(null, 100), readOptions);
    }

    public static StateQuery projectionSnapshotQuery(
            StateGroupId groupId,
            ConfigProjectionSnapshotRequest request,
            ReadOptions readOptions) {
        requireConfigGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_PROJECTION_SNAPSHOT,
                SCHEMA_VERSION,
                encodeProjectionSnapshotRequest(request),
                readOptions);
    }

    public static StateQuery resolveOperationQuery(
            StateGroupId groupId,
            ResolveConfigOperationRequest request,
            ReadOptions readOptions) {
        requireConfigGroup(groupId);
        return new StateQuery(
                groupId,
                QUERY_RESOLVE_OPERATION,
                SCHEMA_VERSION,
                encodeResolveOperationRequest(request),
                readOptions);
    }

    public static WatchRequest changesWatch(
            StateGroupId groupId,
            long afterEventRevision,
            ConfigWatchSelector selector,
            int maxBatchSize,
            ReadOptions readOptions) {
        requireConfigGroup(groupId);
        return new WatchRequest(
                StateRevision.configEvent(groupId, afterEventRevision),
                WATCH_CHANGES,
                SCHEMA_VERSION,
                encodeWatchSelector(selector),
                maxBatchSize,
                readOptions);
    }

    public static byte[] encodeMutation(ConfigMutation value) {
        return encode(output -> {
            writeActor(output, value.actor());
            writeConfigKey(output, value.configKey());
            output.writeLong(value.expectedDecisionRevision());
            writeString(output, value.decisionState().name());
            output.writeBoolean(value.newContent() != null);
            if (value.newContent() != null) {
                writeContentDraft(output, value.newContent());
            }
            output.writeBoolean(value.stableContent() != null);
            if (value.stableContent() != null) {
                writeContentReference(output, value.stableContent());
            }
            writeList(output, value.rules(), ConfigStateCodec::writeRuleDraft);
        });
    }

    public static ConfigMutation decodeMutation(byte[] bytes) throws IOException {
        return decode(bytes, input -> {
            ConfigActor actor = readActor(input);
            ConfigKey configKey = readConfigKey(input);
            long expectedDecisionRevision = input.readLong();
            ConfigDecisionState state = ConfigDecisionState.valueOf(readString(input));
            ConfigContentDraft newContent = input.readBoolean()
                    ? readContentDraft(input)
                    : null;
            ConfigContentReference stableContent = input.readBoolean()
                    ? readContentReference(input)
                    : null;
            return new ConfigMutation(
                    actor,
                    configKey,
                    expectedDecisionRevision,
                    newContent,
                    state,
                    stableContent,
                    readList(input, ConfigStateCodec::readRuleDraft));
        });
    }

    public static byte[] encodeMutationResult(ConfigMutationResult value) {
        return encode(output -> {
            writeDecision(output, value.decision());
            output.writeLong(value.createdContentRevision());
            output.writeLong(value.eventRevision());
        });
    }

    public static ConfigMutationResult decodeMutationResult(byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigMutationResult(
                readDecision(input), input.readLong(), input.readLong()));
    }

    public static byte[] encodeMutationError(ConfigMutationError value) {
        return encode(output -> {
            writeString(output, value.code());
            writeString(output, value.message());
        });
    }

    public static ConfigMutationError decodeMutationError(byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigMutationError(
                readString(input), readString(input)));
    }

    public static byte[] encodeApplicableReleaseRequest(ApplicableReleaseRequest value) {
        return encode(output -> {
            writeConfigKey(output, value.configKey());
            writeIdentity(output, value.identity());
        });
    }

    public static ApplicableReleaseRequest decodeApplicableReleaseRequest(byte[] bytes)
            throws IOException {
        return decode(bytes, input -> new ApplicableReleaseRequest(
                readConfigKey(input), readIdentity(input)));
    }

    public static byte[] encodeApplicableRelease(ApplicableRelease value) {
        return encode(output -> {
            writeString(output, value.state().name());
            if (value.state() != ConfigValueState.MISSING) {
                writeConfigKey(output, value.configKey());
                output.writeLong(value.decisionRevision());
            }
            if (value.found()) {
                writeContent(output, value.content());
                writeString(output, value.matchedRuleId());
            }
        });
    }

    public static ApplicableRelease decodeApplicableRelease(byte[] bytes) throws IOException {
        return decode(bytes, input -> {
            ConfigValueState state = ConfigValueState.valueOf(readString(input));
            if (state == ConfigValueState.MISSING) {
                return ApplicableRelease.missing();
            }
            ConfigKey configKey = readConfigKey(input);
            long decisionRevision = input.readLong();
            if (state == ConfigValueState.TOMBSTONE) {
                return new ApplicableRelease(
                        state, configKey, decisionRevision, null, "");
            }
            return new ApplicableRelease(
                    state,
                    configKey,
                    decisionRevision,
                    readContent(input),
                    readString(input));
        });
    }

    public static byte[] encodeSnapshotRequest(ConfigSnapshotRequest value) {
        return encode(output -> writeList(
                output, value.configKeys(), ConfigStateCodec::writeConfigKey));
    }

    public static ConfigSnapshotRequest decodeSnapshotRequest(byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigSnapshotRequest(
                readList(input, ConfigStateCodec::readConfigKey)));
    }

    public static byte[] encodeWatchSelector(ConfigWatchSelector value) {
        return encode(output -> {
            writeString(output, value.namespace());
            writeString(output, value.group());
            writeList(output, value.configKeys(), ConfigStateCodec::writeConfigKey);
        });
    }

    public static ConfigWatchSelector decodeWatchSelector(byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigWatchSelector(
                readString(input),
                readString(input),
                readList(input, ConfigStateCodec::readConfigKey)));
    }

    public static byte[] encodeSnapshot(ConfigSnapshot value) {
        return encode(output -> {
            output.writeLong(value.eventRevision());
            output.writeLong(value.compactionRevision());
            writeList(output, value.decisions(), ConfigStateCodec::writeDecision);
        });
    }

    public static ConfigSnapshot decodeSnapshot(byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigSnapshot(
                input.readLong(),
                input.readLong(),
                readList(input, ConfigStateCodec::readDecision)));
    }

    public static byte[] encodeProjectionSnapshot(ConfigProjectionSnapshot value) {
        return encode(output -> {
            output.writeLong(value.eventRevision());
            output.writeLong(value.compactionRevision());
            writeList(output, value.entries(), ConfigStateCodec::writeProjectionEntry);
            output.writeBoolean(value.hasMore());
        });
    }

    public static ConfigProjectionSnapshot decodeProjectionSnapshot(byte[] bytes)
            throws IOException {
        return decode(bytes, input -> new ConfigProjectionSnapshot(
                input.readLong(),
                input.readLong(),
                readList(input, ConfigStateCodec::readProjectionEntry),
                input.readBoolean()));
    }

    public static byte[] encodeProjectionSnapshotRequest(
            ConfigProjectionSnapshotRequest value) {
        return encode(output -> {
            output.writeBoolean(value.afterExclusive() != null);
            if (value.afterExclusive() != null) {
                writeConfigKey(output, value.afterExclusive());
            }
            output.writeInt(value.limit());
        });
    }

    public static ConfigProjectionSnapshotRequest decodeProjectionSnapshotRequest(
            byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigProjectionSnapshotRequest(
                input.readBoolean() ? readConfigKey(input) : null,
                input.readInt()));
    }

    public static byte[] encodeChangeEvent(ConfigChangeEvent value) {
        return encode(output -> {
            output.writeLong(value.eventRevision());
            writeConfigKey(output, value.configKey());
            output.writeLong(value.decisionRevision());
        });
    }

    public static ConfigChangeEvent decodeChangeEvent(byte[] bytes) throws IOException {
        return decode(bytes, input -> new ConfigChangeEvent(
                input.readLong(), readConfigKey(input), input.readLong()));
    }

    public static byte[] encodeResolveOperationRequest(ResolveConfigOperationRequest value) {
        return encode(output -> {
            writeActor(output, value.actor());
            writeString(output, value.operationId());
        });
    }

    public static ResolveConfigOperationRequest decodeResolveOperationRequest(byte[] bytes)
            throws IOException {
        return decode(bytes, input -> new ResolveConfigOperationRequest(
                readActor(input), readString(input)));
    }

    public static byte[] encodeResolvedOperation(ResolvedConfigOperation value) {
        return encode(output -> {
            output.writeBoolean(value.found());
            if (value.found()) {
                writeString(output, value.requestHash());
                writeString(output, value.status().name());
                writeString(output, value.resultType());
                writeBytes(output, value.payload());
                writeList(output, value.revisions(), ConfigStateCodec::writeRevision);
            }
        });
    }

    public static ResolvedConfigOperation decodeResolvedOperation(byte[] bytes)
            throws IOException {
        return decode(bytes, input -> {
            if (!input.readBoolean()) {
                return ResolvedConfigOperation.missing();
            }
            return new ResolvedConfigOperation(
                    true,
                    readString(input),
                    ApplyStatus.valueOf(readString(input)),
                    readString(input),
                    readBytes(input),
                    readList(input, ConfigStateCodec::readRevision));
        });
    }

    static void writeActor(DataOutputStream output, ConfigActor value) throws IOException {
        writeString(output, value.tenant());
        writeString(output, value.principal());
    }

    static ConfigActor readActor(DataInputStream input) throws IOException {
        return new ConfigActor(readString(input), readString(input));
    }

    static void writeConfigKey(DataOutputStream output, ConfigKey value) throws IOException {
        writeString(output, value.namespace());
        writeString(output, value.group());
        writeString(output, value.dataId());
    }

    static ConfigKey readConfigKey(DataInputStream input) throws IOException {
        return new ConfigKey(readString(input), readString(input), readString(input));
    }

    static void writeContentDraft(DataOutputStream output, ConfigContentDraft value)
            throws IOException {
        writeString(output, value.contentType());
        output.writeInt(value.schemaVersion());
        writeBytes(output, value.payload());
        writeString(output, value.contentHash());
        writeString(output, value.blobReference());
    }

    static ConfigContentDraft readContentDraft(DataInputStream input) throws IOException {
        return new ConfigContentDraft(
                readString(input),
                input.readInt(),
                readBytes(input),
                readString(input),
                readString(input));
    }

    static void writeContent(DataOutputStream output, ConfigContent value) throws IOException {
        writeConfigKey(output, value.configKey());
        output.writeLong(value.contentRevision());
        writeString(output, value.contentHash());
        writeString(output, value.contentType());
        output.writeInt(value.schemaVersion());
        writeBytes(output, value.payload());
        writeString(output, value.blobReference());
    }

    static ConfigContent readContent(DataInputStream input) throws IOException {
        return new ConfigContent(
                readConfigKey(input),
                input.readLong(),
                readString(input),
                readString(input),
                input.readInt(),
                readBytes(input),
                readString(input));
    }

    static void writeContentReference(
            DataOutputStream output, ConfigContentReference value) throws IOException {
        if (value instanceof ConfigContentReference.NewContent) {
            output.writeByte(1);
        } else if (value instanceof ConfigContentReference.Existing existing) {
            output.writeByte(2);
            output.writeLong(existing.contentRevision());
        } else {
            throw new IOException("Unsupported content reference: " + value);
        }
    }

    static ConfigContentReference readContentReference(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> ConfigContentReference.newContent();
            case 2 -> ConfigContentReference.existing(input.readLong());
            default -> throw new IOException("Unsupported content reference type");
        };
    }

    static void writeRuleDraft(DataOutputStream output, RolloutRuleDraft value)
            throws IOException {
        writeString(output, value.ruleId());
        output.writeLong(value.ruleGeneration());
        output.writeInt(value.selectorVersion());
        output.writeInt(value.priority());
        writeContentReference(output, value.targetContent());
        writeString(output, value.rolloutKey());
        writeString(output, value.selectorType().name());
        writeString(output, value.selectorKey());
        writeList(output, value.selectorValues(), ConfigStateCodec::writeString);
        output.writeInt(value.percentageBasisPoints());
        output.writeLong(value.seed());
        writeString(output, value.status().name());
    }

    static RolloutRuleDraft readRuleDraft(DataInputStream input) throws IOException {
        return new RolloutRuleDraft(
                readString(input),
                input.readLong(),
                input.readInt(),
                input.readInt(),
                readContentReference(input),
                readString(input),
                RolloutSelectorType.valueOf(readString(input)),
                readString(input),
                readList(input, ConfigStateCodec::readString),
                input.readInt(),
                input.readLong(),
                RolloutRuleStatus.valueOf(readString(input)));
    }

    static void writeRule(DataOutputStream output, RolloutRule value) throws IOException {
        writeString(output, value.ruleId());
        output.writeLong(value.ruleGeneration());
        output.writeInt(value.selectorVersion());
        output.writeInt(value.priority());
        output.writeLong(value.targetContentRevision());
        writeString(output, value.rolloutKey());
        writeString(output, value.selectorType().name());
        writeString(output, value.selectorKey());
        writeList(output, value.selectorValues(), ConfigStateCodec::writeString);
        output.writeInt(value.percentageBasisPoints());
        output.writeLong(value.seed());
        writeString(output, value.status().name());
        output.writeLong(value.activationDecisionRevision());
    }

    static RolloutRule readRule(DataInputStream input) throws IOException {
        return new RolloutRule(
                readString(input),
                input.readLong(),
                input.readInt(),
                input.readInt(),
                input.readLong(),
                readString(input),
                RolloutSelectorType.valueOf(readString(input)),
                readString(input),
                readList(input, ConfigStateCodec::readString),
                input.readInt(),
                input.readLong(),
                RolloutRuleStatus.valueOf(readString(input)),
                input.readLong());
    }

    static void writeDecision(DataOutputStream output, ReleaseDecision value)
            throws IOException {
        writeConfigKey(output, value.configKey());
        output.writeLong(value.decisionRevision());
        writeString(output, value.state().name());
        output.writeLong(value.stableContentRevision());
        writeList(output, value.rules(), ConfigStateCodec::writeRule);
    }

    static ReleaseDecision readDecision(DataInputStream input) throws IOException {
        return new ReleaseDecision(
                readConfigKey(input),
                input.readLong(),
                ConfigDecisionState.valueOf(readString(input)),
                input.readLong(),
                readList(input, ConfigStateCodec::readRule));
    }

    static void writeProjectionEntry(
            DataOutputStream output, ConfigProjectionEntry value) throws IOException {
        writeConfigKey(output, value.configKey());
        output.writeLong(value.decisionRevision());
        writeString(output, value.state().name());
        output.writeLong(value.stableContentRevision());
        writeList(output, value.rules(), ConfigStateCodec::writeRolloutDigest);
        writeList(output, value.referencedContents(), ConfigStateCodec::writeContentDigest);
    }

    static ConfigProjectionEntry readProjectionEntry(DataInputStream input)
            throws IOException {
        return new ConfigProjectionEntry(
                readConfigKey(input),
                input.readLong(),
                ConfigDecisionState.valueOf(readString(input)),
                input.readLong(),
                readList(input, ConfigStateCodec::readRolloutDigest),
                readList(input, ConfigStateCodec::readContentDigest));
    }

    static void writeRolloutDigest(
            DataOutputStream output, ConfigRolloutDigest value) throws IOException {
        writeString(output, value.ruleId());
        output.writeLong(value.targetContentRevision());
        writeString(output, value.status().name());
    }

    static ConfigRolloutDigest readRolloutDigest(DataInputStream input)
            throws IOException {
        return new ConfigRolloutDigest(
                readString(input),
                input.readLong(),
                RolloutRuleStatus.valueOf(readString(input)));
    }

    static void writeContentDigest(
            DataOutputStream output, ConfigContentDigest value) throws IOException {
        output.writeLong(value.contentRevision());
        writeString(output, value.contentHash());
        writeString(output, value.contentType());
        output.writeInt(value.schemaVersion());
        writeString(output, value.blobReference());
    }

    static ConfigContentDigest readContentDigest(DataInputStream input)
            throws IOException {
        return new ConfigContentDigest(
                input.readLong(),
                readString(input),
                readString(input),
                input.readInt(),
                readString(input));
    }

    static void writeIdentity(DataOutputStream output, ConfigClientIdentity value)
            throws IOException {
        writeString(output, value.clientInstanceId());
        writeString(output, value.applicationName());
        writeString(output, value.remoteIp());
        output.writeInt(value.trustedTags().size());
        for (Map.Entry<String, String> entry : value.trustedTags().entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    static ConfigClientIdentity readIdentity(DataInputStream input) throws IOException {
        String clientInstanceId = readString(input);
        String applicationName = readString(input);
        String remoteIp = readString(input);
        int size = readSize(input);
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String previous = tags.put(readString(input), readString(input));
            if (previous != null) {
                throw new IOException("Duplicate identity tag");
            }
        }
        return new ConfigClientIdentity(
                clientInstanceId, applicationName, remoteIp, tags);
    }

    static void writeRevision(DataOutputStream output, StateRevision value)
            throws IOException {
        writeString(output, value.groupId().type().name());
        writeString(output, value.groupId().value());
        writeString(output, value.type().name());
        writeString(output, value.scope());
        output.writeLong(value.value());
    }

    static StateRevision readRevision(DataInputStream input) throws IOException {
        StateGroupId groupId = new StateGroupId(
                StateGroupType.valueOf(readString(input)), readString(input));
        return new StateRevision(
                groupId,
                StateRevisionType.valueOf(readString(input)),
                readString(input),
                input.readLong());
    }

    static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAX_BYTES) {
            throw new IOException("Payload exceeds codec limit");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BYTES) {
            throw new IOException("Invalid payload length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of payload");
        }
        return bytes;
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String exceeds codec limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String readString(DataInputStream input) throws IOException {
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

    static <T> void writeList(
            DataOutputStream output, List<T> values, Writer<T> writer) throws IOException {
        if (values.size() > MAX_LIST) {
            throw new IOException("List exceeds codec limit");
        }
        output.writeInt(values.size());
        for (T value : values) {
            writer.write(output, value);
        }
    }

    static <T> List<T> readList(DataInputStream input, Reader<T> reader)
            throws IOException {
        int size = readSize(input);
        List<T> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(reader.read(input));
        }
        return List.copyOf(values);
    }

    static int readSize(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_LIST) {
            throw new IOException("Invalid collection size: " + size);
        }
        return size;
    }

    private static byte[] encode(Encoder encoder) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            encoder.encode(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to encode Config State payload", e);
        }
    }

    private static <T> T decode(byte[] bytes, Decoder<T> decoder) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new IOException("Invalid Config State payload");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid Config State payload magic");
            }
            int version = input.readInt();
            if (version != SCHEMA_VERSION) {
                throw new IOException("Unsupported Config State payload version: " + version);
            }
            T value = decoder.decode(input);
            if (input.read() != -1) {
                throw new IOException("Trailing Config State payload bytes");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Config State payload", e);
        }
    }

    private static void requireConfigGroup(StateGroupId groupId) {
        if (groupId == null || groupId.type() != StateGroupType.CONFIG) {
            throw new IllegalArgumentException("Config State requires a CONFIG group");
        }
    }

    @FunctionalInterface
    interface Encoder {
        void encode(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    interface Decoder<T> {
        T decode(DataInputStream input) throws IOException;
    }

    @FunctionalInterface
    interface Writer<T> {
        void write(DataOutputStream output, T value) throws IOException;
    }

    @FunctionalInterface
    interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
