package cloud.xuantong.config.state;

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
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Deterministic Config Raft state machine.
 *
 * <p>Apply never reads a database, network, wall clock, random source, Socket.D
 * Session, or process-local event bus. Contents, decisions, idempotency
 * records, and the retained Config ChangeLog are committed and snapshotted as
 * one state.</p>
 */
public final class ConfigStateMachine implements StateMachine {
    public static final int SNAPSHOT_SCHEMA_VERSION = 2;
    private static final int SNAPSHOT_MAGIC = 0x58435332;

    private final StateGroupId groupId;
    private final ConfigStateOptions options;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

    private final NavigableMap<ConfigKey, NavigableMap<Long, ConfigContent>> contents =
            new TreeMap<>();
    private final NavigableMap<ConfigKey, ReleaseDecision> decisions = new TreeMap<>();
    private final NavigableMap<Long, ConfigChangeEvent> changeLog = new TreeMap<>();
    private final Map<OperationKey, OperationRecord> operations = new LinkedHashMap<>();

    private long appliedIndex;
    private long eventRevision;
    private long compactionRevision;

    public ConfigStateMachine(StateGroupId groupId) {
        this(groupId, ConfigStateOptions.defaults());
    }

    public ConfigStateMachine(StateGroupId groupId, ConfigStateOptions options) {
        if (groupId == null || groupId.type() != StateGroupType.CONFIG) {
            throw new IllegalArgumentException("ConfigStateMachine requires a CONFIG group");
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
            if (!ConfigStateCodec.COMMAND_MUTATE.equals(command.commandType())
                    || command.schemaVersion() != ConfigStateCodec.SCHEMA_VERSION) {
                return rejected(
                        command.operationId(),
                        "UNSUPPORTED_COMMAND",
                        "Unsupported Config State command or schema version");
            }
            if (command.operationId().length() > 256) {
                return rejected(
                        command.operationId(),
                        "INVALID_OPERATION_ID",
                        "operationId must not exceed 256 characters");
            }

            ConfigMutation mutation;
            try {
                mutation = ConfigStateCodec.decodeMutation(command.payload());
            } catch (IOException | IllegalArgumentException e) {
                return rejected(
                        command.operationId(), "MALFORMED_COMMAND", safeMessage(e));
            }

            OperationKey operationKey = new OperationKey(
                    mutation.actor().idempotencyScope(), command.operationId());
            String requestHash = ConfigStateCodec.requestHash(command);
            OperationRecord existing = operations.get(operationKey);
            if (existing != null) {
                if (!MessageDigest.isEqual(
                        existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                        requestHash.getBytes(StandardCharsets.US_ASCII))) {
                    return rejected(
                            command.operationId(),
                            "OPERATION_ID_CONFLICT",
                            "operationId was already committed with another request");
                }
                ApplyStatus replayStatus = existing.status() == ApplyStatus.APPLIED
                        ? ApplyStatus.UNCHANGED
                        : existing.status();
                return existing.toApplyResult(groupId, command.operationId(), appliedIndex,
                        replayStatus);
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
                case ConfigStateCodec.QUERY_APPLICABLE_RELEASE -> applicableRelease(query);
                case ConfigStateCodec.QUERY_SNAPSHOT -> configSnapshot(query);
                case ConfigStateCodec.QUERY_PROJECTION_SNAPSHOT ->
                        projectionSnapshot(query);
                case ConfigStateCodec.QUERY_RESOLVE_OPERATION -> resolveOperation(query);
                default -> throw new IllegalArgumentException(
                        "Unsupported Config State query: " + query.queryType());
            };
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public WatchBatch watch(WatchRequest request) {
        requireGroup(request.groupId());
        requireSchema(request.schemaVersion());
        if (!ConfigStateCodec.WATCH_CHANGES.equals(request.watchType())) {
            throw new IllegalArgumentException(
                    "Unsupported Config State Watch: " + request.watchType());
        }

        stateLock.readLock().lock();
        try {
            long after = request.afterRevision().value();
            if (after > eventRevision) {
                throw new IllegalArgumentException(
                        "Watch cursor is ahead of the Config event high watermark");
            }
            if (after < compactionRevision) {
                return new WatchBatch(
                        request.afterRevision(),
                        StateRevision.configEvent(groupId, eventRevision),
                        StateRevision.configEvent(groupId, compactionRevision),
                        true,
                        List.of());
            }

            ConfigWatchSelector selector;
            try {
                selector = ConfigStateCodec.decodeWatchSelector(request.selector());
            } catch (IOException e) {
                throw new IllegalArgumentException("Malformed Config Watch selector", e);
            }
            Set<ConfigKey> selected = Set.copyOf(selector.configKeys());
            List<WatchEvent> events = new ArrayList<>();
            long coveredThrough = after;
            for (ConfigChangeEvent event : changeLog.tailMap(after, false).values()) {
                boolean inScope = selector.namespace().equals(event.configKey().namespace())
                        && selector.group().equals(event.configKey().group());
                boolean matches = inScope
                        && (selected.isEmpty() || selected.contains(event.configKey()));
                if (matches && events.size() >= request.maxBatchSize()) {
                    break;
                }
                coveredThrough = event.eventRevision();
                if (matches) {
                    events.add(new WatchEvent(
                            StateRevision.configEvent(groupId, event.eventRevision()),
                            ConfigStateCodec.EVENT_INVALIDATED,
                            ConfigStateCodec.SCHEMA_VERSION,
                            ConfigStateCodec.encodeChangeEvent(event)));
                }
            }
            if (events.size() < request.maxBatchSize()) {
                coveredThrough = eventRevision;
            }
            return new WatchBatch(
                    request.afterRevision(),
                    StateRevision.configEvent(groupId, coveredThrough),
                    StateRevision.configEvent(groupId, compactionRevision),
                    false,
                    events);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public int snapshotSchemaVersion() {
        return SNAPSHOT_SCHEMA_VERSION;
    }

    @Override
    public StateMachineCompatibility compatibility() {
        return StateMachineCompatibility.exact(
                ConfigStateCodec.SCHEMA_VERSION, SNAPSHOT_SCHEMA_VERSION);
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
            data.writeLong(eventRevision);
            data.writeLong(compactionRevision);

            data.writeInt(contents.size());
            for (Map.Entry<ConfigKey, NavigableMap<Long, ConfigContent>> entry
                    : contents.entrySet()) {
                ConfigStateCodec.writeConfigKey(data, entry.getKey());
                data.writeInt(entry.getValue().size());
                for (ConfigContent content : entry.getValue().values()) {
                    ConfigStateCodec.writeContent(data, content);
                }
            }

            data.writeInt(decisions.size());
            for (ReleaseDecision decision : decisions.values()) {
                ConfigStateCodec.writeDecision(data, decision);
            }

            data.writeInt(changeLog.size());
            for (ConfigChangeEvent event : changeLog.values()) {
                data.writeLong(event.eventRevision());
                ConfigStateCodec.writeConfigKey(data, event.configKey());
                data.writeLong(event.decisionRevision());
            }

            data.writeInt(operations.size());
            for (Map.Entry<OperationKey, OperationRecord> entry : operations.entrySet()) {
                ConfigStateCodec.writeString(data, entry.getKey().actorScope());
                ConfigStateCodec.writeString(data, entry.getKey().operationId());
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
                    "Unsupported Config State snapshot schema: " + schemaVersion);
        }
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != SNAPSHOT_MAGIC) {
            throw new IOException("Invalid Config State snapshot magic");
        }
        long restoredAppliedIndex = data.readLong();
        long restoredEventRevision = data.readLong();
        long restoredCompactionRevision = data.readLong();
        if (restoredAppliedIndex < 0
                || restoredEventRevision < 0
                || restoredCompactionRevision < 0
                || restoredCompactionRevision > restoredEventRevision) {
            throw new IOException("Invalid Config State snapshot watermarks");
        }

        NavigableMap<ConfigKey, NavigableMap<Long, ConfigContent>> restoredContents =
                readContents(data);
        NavigableMap<ConfigKey, ReleaseDecision> restoredDecisions = readDecisions(data);
        NavigableMap<Long, ConfigChangeEvent> restoredChangeLog = readChangeLog(data);
        Map<OperationKey, OperationRecord> restoredOperations = readOperations(data);
        validateRestoredState(
                restoredEventRevision,
                restoredCompactionRevision,
                restoredContents,
                restoredDecisions,
                restoredChangeLog,
                restoredOperations);

        stateLock.writeLock().lock();
        try {
            contents.clear();
            contents.putAll(restoredContents);
            decisions.clear();
            decisions.putAll(restoredDecisions);
            changeLog.clear();
            changeLog.putAll(restoredChangeLog);
            operations.clear();
            operations.putAll(restoredOperations);
            appliedIndex = restoredAppliedIndex;
            eventRevision = restoredEventRevision;
            compactionRevision = restoredCompactionRevision;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private OperationRecord mutate(ConfigMutation mutation, String requestHash) {
        ReleaseDecision current = decisions.get(mutation.configKey());
        long currentDecisionRevision = current == null ? 0 : current.decisionRevision();
        if (mutation.expectedDecisionRevision() != currentDecisionRevision) {
            return operationError(
                    requestHash,
                    "DECISION_REVISION_CONFLICT",
                    "Expected decision revision " + mutation.expectedDecisionRevision()
                            + " but current revision is " + currentDecisionRevision);
        }
        if (mutation.rules().size() > options.maxRulesPerDecision()) {
            return operationError(
                    requestHash,
                    "TOO_MANY_RULES",
                    "Decision exceeds the configured rollout-rule limit");
        }
        if (currentDecisionRevision == Long.MAX_VALUE || eventRevision == Long.MAX_VALUE) {
            return operationError(
                    requestHash, "REVISION_OVERFLOW", "Config revision space is exhausted");
        }

        NavigableMap<Long, ConfigContent> keyContents = contents.get(mutation.configKey());
        long newContentRevision = 0;
        ConfigContent newContent = null;
        if (mutation.newContent() != null) {
            long lastContentRevision = keyContents == null || keyContents.isEmpty()
                    ? 0 : keyContents.lastKey();
            if (lastContentRevision == Long.MAX_VALUE) {
                return operationError(
                        requestHash,
                        "CONTENT_REVISION_OVERFLOW",
                        "Content revision space is exhausted");
            }
            newContentRevision = lastContentRevision + 1;
            try {
                newContent = createContent(
                        mutation.configKey(), newContentRevision, mutation.newContent());
            } catch (IllegalArgumentException e) {
                return operationError(requestHash, "INVALID_CONTENT", safeMessage(e));
            }
        }

        long decisionRevision = currentDecisionRevision + 1;
        long stableContentRevision = 0;
        List<RolloutRule> rules = List.of();
        if (mutation.decisionState() == ConfigDecisionState.ACTIVE) {
            try {
                stableContentRevision = resolveContentReference(
                        mutation.stableContent(), newContentRevision, keyContents);
                List<RolloutRule> resolvedRules = new ArrayList<>(mutation.rules().size());
                for (RolloutRuleDraft draft : mutation.rules()) {
                    long targetRevision = resolveContentReference(
                            draft.targetContent(), newContentRevision, keyContents);
                    resolvedRules.add(new RolloutRule(
                            draft.ruleId(),
                            draft.ruleGeneration(),
                            draft.selectorVersion(),
                            draft.priority(),
                            targetRevision,
                            draft.rolloutKey(),
                            draft.selectorType(),
                            draft.selectorKey(),
                            draft.selectorValues(),
                            draft.percentageBasisPoints(),
                            draft.seed(),
                            draft.status(),
                            decisionRevision));
                }
                rules = resolvedRules.stream().sorted(RolloutRule.SELECTION_ORDER).toList();
                validateContentExists(stableContentRevision, newContentRevision, keyContents);
                for (RolloutRule rule : rules) {
                    validateContentExists(
                            rule.targetContentRevision(), newContentRevision, keyContents);
                }
            } catch (IllegalArgumentException e) {
                return operationError(requestHash, "INVALID_DECISION", safeMessage(e));
            }
        }

        ReleaseDecision decision;
        try {
            decision = new ReleaseDecision(
                    mutation.configKey(), decisionRevision, mutation.decisionState(),
                    stableContentRevision, rules);
        } catch (IllegalArgumentException e) {
            return operationError(requestHash, "INVALID_RULES", safeMessage(e));
        }

        long nextEventRevision = eventRevision + 1;
        ConfigChangeEvent event = new ConfigChangeEvent(
                nextEventRevision, mutation.configKey(), decisionRevision);

        if (newContent != null) {
            contents.computeIfAbsent(mutation.configKey(), ignored -> new TreeMap<>())
                    .put(newContentRevision, newContent);
        }
        decisions.put(mutation.configKey(), decision);
        eventRevision = nextEventRevision;
        changeLog.put(nextEventRevision, event);
        compactChangeLog();

        ConfigMutationResult result = new ConfigMutationResult(
                decision, newContentRevision, nextEventRevision);
        List<StateRevision> revisions = List.of(
                StateRevision.configDecision(
                        groupId, mutation.configKey().canonicalName(), decisionRevision),
                StateRevision.configEvent(groupId, nextEventRevision));
        return new OperationRecord(
                requestHash,
                ApplyStatus.APPLIED,
                ConfigStateCodec.RESULT_MUTATION,
                ConfigStateCodec.encodeMutationResult(result),
                revisions);
    }

    private QueryResult applicableRelease(StateQuery query) {
        ApplicableReleaseRequest request;
        try {
            request = ConfigStateCodec.decodeApplicableReleaseRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed applicable-release query", e);
        }
        ReleaseDecision decision = decisions.get(request.configKey());
        ApplicableRelease result;
        long decisionRevision = 0;
        if (decision == null) {
            result = ApplicableRelease.missing();
        } else if (decision.tombstone()) {
            decisionRevision = decision.decisionRevision();
            result = ApplicableRelease.tombstone(decision);
        } else {
            decisionRevision = decision.decisionRevision();
            result = ConfigReleaseSelector.select(
                    decision, request.identity(), contents.get(request.configKey()));
        }
        return new QueryResult(
                groupId,
                appliedIndex,
                false,
                ConfigStateCodec.RESULT_APPLICABLE_RELEASE,
                ConfigStateCodec.encodeApplicableRelease(result),
                List.of(
                        StateRevision.configDecision(
                                groupId,
                                request.configKey().canonicalName(),
                                decisionRevision),
                        StateRevision.configEvent(groupId, eventRevision)));
    }

    private QueryResult configSnapshot(StateQuery query) {
        ConfigSnapshotRequest request;
        try {
            request = ConfigStateCodec.decodeSnapshotRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed Config snapshot query", e);
        }
        List<ReleaseDecision> selected;
        if (request.configKeys().isEmpty()) {
            selected = List.copyOf(decisions.values());
        } else {
            selected = request.configKeys().stream()
                    .map(decisions::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        ConfigSnapshot snapshot = new ConfigSnapshot(
                eventRevision, compactionRevision, selected);
        List<StateRevision> revisions = new ArrayList<>(selected.size() + 1);
        for (ReleaseDecision decision : selected) {
            revisions.add(StateRevision.configDecision(
                    groupId,
                    decision.configKey().canonicalName(),
                    decision.decisionRevision()));
        }
        revisions.add(StateRevision.configEvent(groupId, eventRevision));
        return new QueryResult(
                groupId,
                appliedIndex,
                false,
                ConfigStateCodec.RESULT_SNAPSHOT,
                ConfigStateCodec.encodeSnapshot(snapshot),
                revisions);
    }

    private QueryResult projectionSnapshot(StateQuery query) {
        ConfigProjectionSnapshotRequest request;
        try {
            request = ConfigStateCodec.decodeProjectionSnapshotRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Malformed Config projection snapshot query", e);
        }
        NavigableMap<ConfigKey, ReleaseDecision> selected =
                request.afterExclusive() == null
                        ? decisions
                        : decisions.tailMap(request.afterExclusive(), false);
        List<ReleaseDecision> page = selected.values().stream()
                .limit((long) request.limit() + 1L)
                .toList();
        boolean hasMore = page.size() > request.limit();
        if (hasMore) {
            page = page.subList(0, request.limit());
        }
        List<ConfigProjectionEntry> entries = page.stream()
                .map(decision -> {
                    Set<Long> referenced = new LinkedHashSet<>();
                    if (decision.active()) {
                        referenced.add(decision.stableContentRevision());
                        decision.rules().forEach(
                                rule -> referenced.add(rule.targetContentRevision()));
                    }
                    NavigableMap<Long, ConfigContent> versions =
                            contents.getOrDefault(decision.configKey(), new TreeMap<>());
                    List<ConfigContentDigest> digests = referenced.stream()
                            .sorted()
                            .map(revision -> {
                                ConfigContent content = versions.get(revision);
                                if (content == null) {
                                    throw new IllegalStateException(
                                            "Decision references missing content revision "
                                                    + revision);
                                }
                                return ConfigContentDigest.from(content);
                            })
                            .toList();
                    return new ConfigProjectionEntry(
                            decision.configKey(),
                            decision.decisionRevision(),
                            decision.state(),
                            decision.stableContentRevision(),
                            decision.rules().stream()
                                    .map(ConfigRolloutDigest::from)
                                    .toList(),
                            digests);
                })
                .toList();
        ConfigProjectionSnapshot snapshot = new ConfigProjectionSnapshot(
                eventRevision, compactionRevision, entries, hasMore);
        return new QueryResult(
                groupId,
                appliedIndex,
                false,
                ConfigStateCodec.RESULT_PROJECTION_SNAPSHOT,
                ConfigStateCodec.encodeProjectionSnapshot(snapshot),
                List.of(StateRevision.configEvent(groupId, eventRevision)));
    }

    private QueryResult resolveOperation(StateQuery query) {
        ResolveConfigOperationRequest request;
        try {
            request = ConfigStateCodec.decodeResolveOperationRequest(query.payload());
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed resolve-operation query", e);
        }
        OperationRecord record = operations.get(new OperationKey(
                request.actor().idempotencyScope(), request.operationId()));
        ResolvedConfigOperation result = record == null
                ? ResolvedConfigOperation.missing()
                : record.resolved();
        return new QueryResult(
                groupId,
                appliedIndex,
                false,
                ConfigStateCodec.RESULT_RESOLVED_OPERATION,
                ConfigStateCodec.encodeResolvedOperation(result),
                List.of(StateRevision.configEvent(groupId, eventRevision)));
    }

    private ConfigContent createContent(
            ConfigKey key, long revision, ConfigContentDraft draft) {
        if (draft.inline() && draft.payload().length > options.maxInlineContentBytes()) {
            throw new IllegalArgumentException(
                    "Inline content exceeds " + options.maxInlineContentBytes() + " bytes");
        }
        String contentHash;
        if (draft.inline()) {
            String calculated = HexFormat.of().formatHex(
                    ConfigReleaseSelector.sha256(draft.payload()));
            if (!draft.contentHash().isEmpty() && !MessageDigest.isEqual(
                    calculated.getBytes(StandardCharsets.US_ASCII),
                    draft.contentHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Inline contentHash does not match payload");
            }
            contentHash = calculated;
        } else {
            contentHash = draft.contentHash();
        }
        return new ConfigContent(
                key,
                revision,
                contentHash,
                draft.contentType(),
                draft.schemaVersion(),
                draft.payload(),
                draft.blobReference());
    }

    private long resolveContentReference(
            ConfigContentReference reference,
            long newContentRevision,
            NavigableMap<Long, ConfigContent> keyContents) {
        if (reference instanceof ConfigContentReference.NewContent) {
            if (newContentRevision < 1) {
                throw new IllegalArgumentException(
                        "Decision references NEW_CONTENT without a new content payload");
            }
            return newContentRevision;
        }
        long revision = ((ConfigContentReference.Existing) reference).contentRevision();
        validateContentExists(revision, newContentRevision, keyContents);
        return revision;
    }

    private static void validateContentExists(
            long revision,
            long newContentRevision,
            NavigableMap<Long, ConfigContent> keyContents) {
        if (revision == newContentRevision && newContentRevision > 0) {
            return;
        }
        if (keyContents == null || !keyContents.containsKey(revision)) {
            throw new IllegalArgumentException(
                    "Decision references missing content revision " + revision);
        }
    }

    private void compactChangeLog() {
        while (changeLog.size() > options.changeLogCapacity()) {
            Map.Entry<Long, ConfigChangeEvent> removed = changeLog.pollFirstEntry();
            compactionRevision = removed.getKey();
        }
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

    private ApplyResult rejected(String operationId, String code, String message) {
        return new ApplyResult(
                groupId,
                operationId,
                ApplyStatus.REJECTED,
                appliedIndex,
                ConfigStateCodec.RESULT_MUTATION_ERROR,
                ConfigStateCodec.encodeMutationError(new ConfigMutationError(code, message)),
                List.of());
    }

    private OperationRecord operationError(String requestHash, String code, String message) {
        return new OperationRecord(
                requestHash,
                ApplyStatus.REJECTED,
                ConfigStateCodec.RESULT_MUTATION_ERROR,
                ConfigStateCodec.encodeMutationError(new ConfigMutationError(code, message)),
                List.of());
    }

    private NavigableMap<ConfigKey, NavigableMap<Long, ConfigContent>> readContents(
            DataInputStream data) throws IOException {
        int keyCount = ConfigStateCodec.readSize(data);
        NavigableMap<ConfigKey, NavigableMap<Long, ConfigContent>> restored = new TreeMap<>();
        for (int i = 0; i < keyCount; i++) {
            ConfigKey key = ConfigStateCodec.readConfigKey(data);
            int contentCount = ConfigStateCodec.readSize(data);
            NavigableMap<Long, ConfigContent> versions = new TreeMap<>();
            for (int j = 0; j < contentCount; j++) {
                ConfigContent content = ConfigStateCodec.readContent(data);
                if (!key.equals(content.configKey())
                        || versions.put(content.contentRevision(), content) != null) {
                    throw new IOException("Invalid or duplicate ConfigContent in snapshot");
                }
                if (content.inline()
                        && content.payload().length > options.maxInlineContentBytes()) {
                    throw new IOException("Snapshot content exceeds configured inline limit");
                }
                if (content.inline()) {
                    String calculatedHash = HexFormat.of().formatHex(
                            ConfigReleaseSelector.sha256(content.payload()));
                    if (!MessageDigest.isEqual(
                            calculatedHash.getBytes(StandardCharsets.US_ASCII),
                            content.contentHash().getBytes(StandardCharsets.US_ASCII))) {
                        throw new IOException("Snapshot contentHash does not match payload");
                    }
                }
            }
            if (versions.isEmpty() || restored.put(key, versions) != null) {
                throw new IOException("Invalid or duplicate content key in snapshot");
            }
        }
        return restored;
    }

    private NavigableMap<ConfigKey, ReleaseDecision> readDecisions(DataInputStream data)
            throws IOException {
        int count = ConfigStateCodec.readSize(data);
        NavigableMap<ConfigKey, ReleaseDecision> restored = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            ReleaseDecision decision = ConfigStateCodec.readDecision(data);
            if (decision.rules().size() > options.maxRulesPerDecision()
                    || restored.put(decision.configKey(), decision) != null) {
                throw new IOException("Invalid or duplicate ReleaseDecision in snapshot");
            }
        }
        return restored;
    }

    private NavigableMap<Long, ConfigChangeEvent> readChangeLog(DataInputStream data)
            throws IOException {
        int count = ConfigStateCodec.readSize(data);
        if (count > options.changeLogCapacity()) {
            throw new IOException("Snapshot ChangeLog exceeds configured capacity");
        }
        NavigableMap<Long, ConfigChangeEvent> restored = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            ConfigChangeEvent event = new ConfigChangeEvent(
                    data.readLong(), ConfigStateCodec.readConfigKey(data), data.readLong());
            if (restored.put(event.eventRevision(), event) != null) {
                throw new IOException("Duplicate Config ChangeLog revision in snapshot");
            }
        }
        return restored;
    }

    private Map<OperationKey, OperationRecord> readOperations(DataInputStream data)
            throws IOException {
        int count = ConfigStateCodec.readSize(data);
        if (count > options.maxOperationRecords()) {
            throw new IOException("Snapshot operation history exceeds configured capacity");
        }
        Map<OperationKey, OperationRecord> restored = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            OperationKey key = new OperationKey(
                    ConfigStateCodec.readString(data), ConfigStateCodec.readString(data));
            OperationRecord record = readOperationRecord(data);
            if (restored.put(key, record) != null) {
                throw new IOException("Duplicate operation record in snapshot");
            }
        }
        return restored;
    }

    private void validateRestoredState(
            long restoredEventRevision,
            long restoredCompactionRevision,
            NavigableMap<ConfigKey, NavigableMap<Long, ConfigContent>> restoredContents,
            NavigableMap<ConfigKey, ReleaseDecision> restoredDecisions,
            NavigableMap<Long, ConfigChangeEvent> restoredChangeLog,
            Map<OperationKey, OperationRecord> restoredOperations) throws IOException {
        if (!restoredChangeLog.isEmpty()) {
            if (restoredChangeLog.firstKey() <= restoredCompactionRevision
                    || restoredChangeLog.lastKey() > restoredEventRevision) {
                throw new IOException("Snapshot ChangeLog watermarks are inconsistent");
            }
        }
        long expectedEvent = restoredCompactionRevision + 1;
        for (long revision : restoredChangeLog.keySet()) {
            if (revision != expectedEvent++) {
                throw new IOException("Snapshot ChangeLog contains a revision gap");
            }
        }
        if (restoredEventRevision > restoredCompactionRevision
                && (restoredChangeLog.isEmpty()
                || restoredChangeLog.lastKey() != restoredEventRevision)) {
            throw new IOException("Snapshot ChangeLog does not reach its high watermark");
        }
        for (ReleaseDecision decision : restoredDecisions.values()) {
            NavigableMap<Long, ConfigContent> versions =
                    restoredContents.get(decision.configKey());
            if (decision.tombstone()) {
                continue;
            }
            if (versions == null || !versions.containsKey(decision.stableContentRevision())) {
                throw new IOException("Snapshot decision references missing stable content");
            }
            for (RolloutRule rule : decision.rules()) {
                if (!versions.containsKey(rule.targetContentRevision())) {
                    throw new IOException("Snapshot rule references missing target content");
                }
            }
        }
        for (ConfigChangeEvent event : restoredChangeLog.values()) {
            ReleaseDecision decision = restoredDecisions.get(event.configKey());
            if (decision == null || event.decisionRevision() > decision.decisionRevision()) {
                throw new IOException("Snapshot ChangeLog references an invalid decision");
            }
        }
        for (OperationRecord record : restoredOperations.values()) {
            for (StateRevision revision : record.revisions()) {
                if (!groupId.equals(revision.groupId())) {
                    throw new IOException("Snapshot operation belongs to another State Group");
                }
            }
        }
    }

    private static void writeOperationRecord(DataOutputStream data, OperationRecord value)
            throws IOException {
        ConfigStateCodec.writeString(data, value.requestHash());
        ConfigStateCodec.writeString(data, value.status().name());
        ConfigStateCodec.writeString(data, value.resultType());
        ConfigStateCodec.writeBytes(data, value.payload());
        ConfigStateCodec.writeList(
                data, value.revisions(), ConfigStateCodec::writeRevision);
    }

    private static OperationRecord readOperationRecord(DataInputStream data) throws IOException {
        return new OperationRecord(
                ConfigStateCodec.readString(data),
                ApplyStatus.valueOf(ConfigStateCodec.readString(data)),
                ConfigStateCodec.readString(data),
                ConfigStateCodec.readBytes(data),
                ConfigStateCodec.readList(data, ConfigStateCodec::readRevision));
    }

    private void requireGroup(StateGroupId target) {
        if (!groupId.equals(target)) {
            throw new IllegalArgumentException(
                    "Config State message targets another State Group: " + target);
        }
    }

    private static void requireSchema(int schemaVersion) {
        if (schemaVersion != ConfigStateCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Config State schema version: " + schemaVersion);
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private record OperationKey(String actorScope, String operationId) {
        private OperationKey {
            if (actorScope == null || actorScope.isBlank()) {
                throw new IllegalArgumentException("actorScope must not be blank");
            }
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId must not be blank");
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
            ConfigContentDraft.validateSha256(requestHash);
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

        private ResolvedConfigOperation resolved() {
            return new ResolvedConfigOperation(
                    true, requestHash, status, resultType, payload, revisions);
        }
    }
}
