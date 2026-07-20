package cloud.xuantong.config.state;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStateMachineTest {
    private static final ConfigActor ACTOR = new ConfigActor("tenant-a", "admin-a");
    private static final ConfigKey KEY = new ConfigKey("public", "DEFAULT_GROUP", "demo.json");

    private StateGroupId groupId;
    private ConfigStateMachine stateMachine;
    private long logIndex;

    @BeforeEach
    void setUp() {
        groupId = StateGroupId.config("config-test");
        stateMachine = new ConfigStateMachine(groupId);
    }

    @Test
    void publishesImmutableContentAndFetchesApplicableRelease() throws Exception {
        ApplyResult applied = apply("op-publish", stablePublish(KEY, 0, "{\"v\":1}"));

        assertEquals(ApplyStatus.APPLIED, applied.status());
        ConfigMutationResult mutation = ConfigStateCodec.decodeMutationResult(applied.payload());
        assertEquals(1, mutation.createdContentRevision());
        assertEquals(1, mutation.decision().decisionRevision());
        assertEquals(1, mutation.eventRevision());
        assertEquals(2, applied.revisions().size());

        ApplicableRelease release = fetch(KEY, identity("client-a"));
        assertTrue(release.found());
        assertEquals(1, release.decisionRevision());
        assertEquals(1, release.content().contentRevision());
        assertArrayEquals(bytes("{\"v\":1}"), release.content().payload());
        assertTrue(release.matchedRuleId().isEmpty());
    }

    @Test
    void graySelectionAndRollbackUseOneApplicableReleaseFunction() throws Exception {
        apply("op-stable", stablePublish(KEY, 0, "stable"));

        RolloutRuleDraft rule = new RolloutRuleDraft(
                "rule-gray-client",
                1,
                1,
                100,
                ConfigContentReference.newContent(),
                "rollout-2026-07",
                RolloutSelectorType.CLIENT_INSTANCE_ID,
                "",
                List.of("client-gray"),
                0,
                7,
                RolloutRuleStatus.ACTIVE);
        ConfigMutation gray = new ConfigMutation(
                ACTOR,
                KEY,
                1,
                ConfigContentDraft.inline("text", 1, bytes("candidate")),
                ConfigContentReference.existing(1),
                List.of(rule));
        ConfigMutationResult grayResult = ConfigStateCodec.decodeMutationResult(
                apply("op-gray", gray).payload());

        assertEquals(2, grayResult.decision().decisionRevision());
        assertEquals(2, grayResult.createdContentRevision());
        ApplicableRelease selected = fetch(KEY, identity("client-gray"));
        ApplicableRelease baseline = fetch(KEY, identity("client-stable"));
        assertEquals("candidate", text(selected.content().payload()));
        assertEquals("rule-gray-client", selected.matchedRuleId());
        assertEquals("stable", text(baseline.content().payload()));
        assertTrue(baseline.matchedRuleId().isEmpty());

        ConfigMutation rollback = new ConfigMutation(
                ACTOR,
                KEY,
                2,
                null,
                ConfigContentReference.existing(1),
                List.of());
        ConfigMutationResult rollbackResult = ConfigStateCodec.decodeMutationResult(
                apply("op-rollback", rollback).payload());

        assertEquals(3, rollbackResult.decision().decisionRevision());
        assertEquals(3, rollbackResult.eventRevision());
        ApplicableRelease afterRollback = fetch(KEY, identity("client-gray"));
        assertEquals(3, afterRollback.decisionRevision());
        assertEquals("stable", text(afterRollback.content().payload()));
        assertTrue(afterRollback.matchedRuleId().isEmpty());
    }

    @Test
    void projectionSnapshotContainsOnlyReferencedContentDigests() throws Exception {
        apply("projection-stable", stablePublish(KEY, 0, "stable"));
        RolloutRuleDraft rule = new RolloutRuleDraft(
                "projection-rule",
                1,
                1,
                100,
                ConfigContentReference.newContent(),
                "projection-rollout",
                RolloutSelectorType.CLIENT_INSTANCE_ID,
                "",
                List.of("client-a"),
                0,
                7,
                RolloutRuleStatus.ACTIVE);
        apply("projection-gray", new ConfigMutation(
                ACTOR,
                KEY,
                1,
                ConfigContentDraft.inline("text", 1, bytes("candidate")),
                ConfigContentReference.existing(1),
                List.of(rule)));

        QueryResult result = stateMachine.query(
                ConfigStateCodec.projectionSnapshotQuery(
                        groupId, ReadOptions.linearizable()));
        ConfigProjectionSnapshot snapshot =
                ConfigStateCodec.decodeProjectionSnapshot(result.payload());

        assertEquals(ConfigStateCodec.RESULT_PROJECTION_SNAPSHOT, result.resultType());
        assertEquals(2, snapshot.eventRevision());
        assertEquals(1, snapshot.entries().size());
        ConfigProjectionEntry entry = snapshot.entries().getFirst();
        assertEquals(2, entry.decisionRevision());
        assertEquals(List.of(1L, 2L), entry.referencedContents().stream()
                .map(ConfigContentDigest::contentRevision).toList());
        assertEquals(64, entry.referencedContents().getFirst().contentHash().length());
    }

    @Test
    void projectionSnapshotPagesByConfigKeyWithStableWatermark() throws Exception {
        ConfigKey firstKey = new ConfigKey("public", "g", "a");
        ConfigKey secondKey = new ConfigKey("public", "g", "b");
        ConfigKey thirdKey = new ConfigKey("public", "g", "c");
        apply("page-a", stablePublish(firstKey, 0, "a"));
        apply("page-b", stablePublish(secondKey, 0, "b"));
        apply("page-c", stablePublish(thirdKey, 0, "c"));

        ConfigProjectionSnapshot first = ConfigStateCodec.decodeProjectionSnapshot(
                stateMachine.query(ConfigStateCodec.projectionSnapshotQuery(
                        groupId,
                        new ConfigProjectionSnapshotRequest(null, 2),
                        ReadOptions.linearizable())).payload());
        ConfigProjectionSnapshot second = ConfigStateCodec.decodeProjectionSnapshot(
                stateMachine.query(ConfigStateCodec.projectionSnapshotQuery(
                        groupId,
                        new ConfigProjectionSnapshotRequest(
                                first.entries().getLast().configKey(), 2),
                        ReadOptions.linearizable())).payload());

        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertEquals(List.of(firstKey, secondKey), first.entries().stream()
                .map(ConfigProjectionEntry::configKey).toList());
        assertEquals(List.of(thirdKey), second.entries().stream()
                .map(ConfigProjectionEntry::configKey).toList());
        assertEquals(first.eventRevision(), second.eventRevision());
    }

    @Test
    void tombstoneSurvivesSnapshotAndAllowsRepublishAndHistoricalRollback() throws Exception {
        apply("op-publish", stablePublish(KEY, 0, "v1"));
        ConfigMutation tombstone = new ConfigMutation(
                ACTOR,
                KEY,
                1,
                null,
                ConfigDecisionState.TOMBSTONE,
                null,
                List.of());

        ConfigMutationResult deleted = ConfigStateCodec.decodeMutationResult(
                apply("op-tombstone", tombstone).payload());

        assertEquals(ConfigDecisionState.TOMBSTONE, deleted.decision().state());
        assertEquals(0, deleted.createdContentRevision());
        assertEquals(2, deleted.decision().decisionRevision());
        assertEquals(2, deleted.eventRevision());
        ApplicableRelease deletedFetch = fetch(KEY, identity("client-a"));
        assertFalse(deletedFetch.found());
        assertTrue(deletedFetch.tombstone());
        assertEquals(KEY, deletedFetch.configKey());
        assertEquals(2, deletedFetch.decisionRevision());

        ByteArrayOutputStream snapshotBytes = new ByteArrayOutputStream();
        stateMachine.writeSnapshot(snapshotBytes);
        ConfigStateMachine restored = new ConfigStateMachine(groupId);
        restored.installSnapshot(
                stateMachine.snapshotSchemaVersion(),
                new ByteArrayInputStream(snapshotBytes.toByteArray()));
        stateMachine = restored;
        assertTrue(fetch(KEY, identity("client-after-restart")).tombstone());

        ConfigMutationResult republished = ConfigStateCodec.decodeMutationResult(
                apply("op-republish", stablePublish(KEY, 2, "v2")).payload());
        assertEquals(ConfigDecisionState.ACTIVE, republished.decision().state());
        assertEquals(2, republished.createdContentRevision());
        assertEquals(3, republished.decision().decisionRevision());
        assertEquals("v2", text(fetch(KEY, identity("client-a")).content().payload()));

        ConfigMutation rollback = new ConfigMutation(
                ACTOR,
                KEY,
                3,
                null,
                ConfigContentReference.existing(1),
                List.of());
        ConfigMutationResult restoredHistorical = ConfigStateCodec.decodeMutationResult(
                apply("op-restore-v1", rollback).payload());
        assertEquals(4, restoredHistorical.decision().decisionRevision());
        assertEquals(1, restoredHistorical.decision().stableContentRevision());
        assertEquals("v1", text(fetch(KEY, identity("client-a")).content().payload()));
    }

    @Test
    void percentageSelectionIsStableAndUsesFullBasisPointRange() {
        RolloutRule rule = new RolloutRule(
                "percentage",
                1,
                1,
                10,
                2,
                "rollout-key",
                RolloutSelectorType.PERCENTAGE,
                "",
                List.of(),
                3500,
                99,
                RolloutRuleStatus.ACTIVE,
                2);

        int first = ConfigReleaseSelector.percentageBucket(rule, "instance-a");
        int second = ConfigReleaseSelector.percentageBucket(rule, "instance-a");
        int another = ConfigReleaseSelector.percentageBucket(rule, "instance-b");

        assertEquals(first, second);
        assertTrue(first >= 0 && first < 10_000);
        assertTrue(another >= 0 && another < 10_000);
        assertNotEquals(first, another);
        assertEquals(first < 3500,
                ConfigReleaseSelector.matches(rule, identity("instance-a")));
    }

    @Test
    void duplicateOperationReplaysWithoutAdvancingBusinessRevisions() throws Exception {
        ConfigMutation publish = stablePublish(KEY, 0, "v1");
        StateCommand original = ConfigStateCodec.mutationCommand(groupId, "same-op", publish);

        ApplyResult first = apply(original);
        ApplyResult replay = apply(original);

        assertEquals(ApplyStatus.APPLIED, first.status());
        assertEquals(ApplyStatus.UNCHANGED, replay.status());
        assertArrayEquals(first.payload(), replay.payload());
        ConfigSnapshot snapshot = snapshot();
        assertEquals(1, snapshot.eventRevision());
        assertEquals(1, snapshot.decisions().getFirst().decisionRevision());

        ConfigMutation changed = stablePublish(KEY, 0, "different");
        ApplyResult conflict = apply(ConfigStateCodec.mutationCommand(
                groupId, "same-op", changed));
        assertEquals(ApplyStatus.REJECTED, conflict.status());
        assertEquals("OPERATION_ID_CONFLICT",
                ConfigStateCodec.decodeMutationError(conflict.payload()).code());

        QueryResult resolvedQuery = stateMachine.query(ConfigStateCodec.resolveOperationQuery(
                groupId,
                new ResolveConfigOperationRequest(ACTOR, "same-op"),
                ReadOptions.linearizable()));
        ResolvedConfigOperation resolved = ConfigStateCodec.decodeResolvedOperation(
                resolvedQuery.payload());
        assertTrue(resolved.found());
        assertEquals(ApplyStatus.APPLIED, resolved.status());
        assertArrayEquals(first.payload(), resolved.payload());
    }

    @Test
    void operationReplayWindowCompactsOldestRecordsWithoutPermanentWriteOutage()
            throws Exception {
        stateMachine = new ConfigStateMachine(
                groupId, new ConfigStateOptions(1024, 10, 10, 2, 10));

        StateCommand first = ConfigStateCodec.mutationCommand(
                groupId, "op-1", stablePublish(KEY, 0, "v1"));
        apply(first);
        StateCommand second = ConfigStateCodec.mutationCommand(
                groupId, "op-2", stablePublish(KEY, 1, "v2"));
        apply(second);
        apply("op-3", stablePublish(KEY, 2, "v3"));

        ResolvedConfigOperation compacted = ConfigStateCodec.decodeResolvedOperation(
                stateMachine.query(ConfigStateCodec.resolveOperationQuery(
                        groupId,
                        new ResolveConfigOperationRequest(ACTOR, "op-1"),
                        ReadOptions.linearizable())).payload());
        assertFalse(compacted.found());
        assertEquals(ApplyStatus.UNCHANGED, apply(second).status());
        assertEquals(3, snapshot().eventRevision());
    }

    @Test
    void rejectedMutationLeavesNoPartialContentOrRevision() throws Exception {
        RolloutRuleDraft referencesNew = new RolloutRuleDraft(
                "new-target",
                1,
                1,
                10,
                ConfigContentReference.newContent(),
                "rollout",
                RolloutSelectorType.CLIENT_INSTANCE_ID,
                "",
                List.of("client-a"),
                0,
                1,
                RolloutRuleStatus.ACTIVE);
        ConfigMutation invalid = new ConfigMutation(
                ACTOR,
                KEY,
                0,
                ConfigContentDraft.inline("text", 1, bytes("orphan")),
                ConfigContentReference.existing(999),
                List.of(referencesNew));

        ApplyResult rejected = apply("op-invalid", invalid);
        assertEquals(ApplyStatus.REJECTED, rejected.status());
        assertEquals("INVALID_DECISION",
                ConfigStateCodec.decodeMutationError(rejected.payload()).code());
        assertEquals(0, snapshot().eventRevision());

        ConfigMutationResult valid = ConfigStateCodec.decodeMutationResult(
                apply("op-valid", stablePublish(KEY, 0, "first-real-content")).payload());
        assertEquals(1, valid.createdContentRevision());
        assertEquals(1, valid.decision().decisionRevision());
    }

    @Test
    void changeLogCompactionAndFilteredWatchPreserveCoveredWatermark() throws Exception {
        stateMachine = new ConfigStateMachine(
                groupId, new ConfigStateOptions(1024, 2, 100, 10));
        ConfigKey first = new ConfigKey("public", "g", "first");
        ConfigKey second = new ConfigKey("public", "g", "second");
        apply("op-1", stablePublish(first, 0, "one"));
        apply("op-2", stablePublish(second, 0, "two"));
        apply("op-3", stablePublish(first, 1, "three"));

        ConfigSnapshot snapshot = snapshot();
        assertEquals(3, snapshot.eventRevision());
        assertEquals(1, snapshot.compactionRevision());

        WatchBatch compacted = stateMachine.watch(ConfigStateCodec.changesWatch(
                groupId,
                0,
                new ConfigWatchSelector("public", "g", List.of()),
                10,
                ReadOptions.linearizable()));
        assertTrue(compacted.resetRequired());
        assertTrue(compacted.events().isEmpty());

        WatchBatch filtered = stateMachine.watch(ConfigStateCodec.changesWatch(
                groupId,
                1,
                new ConfigWatchSelector("public", "g", List.of(first)),
                10,
                ReadOptions.linearizable()));
        assertFalse(filtered.resetRequired());
        assertEquals(3, filtered.coveredThrough().value());
        assertEquals(1, filtered.events().size());
        ConfigChangeEvent event = ConfigStateCodec.decodeChangeEvent(
                filtered.events().getFirst().payload());
        assertEquals(first, event.configKey());
        assertEquals(3, event.eventRevision());
    }

    @Test
    void emptyWatchKeyListStillEnforcesNamespaceAndGroupScope() throws Exception {
        ConfigKey selected = new ConfigKey("public", "orders", "app.yml");
        ConfigKey otherGroup = new ConfigKey("public", "payments", "app.yml");
        ConfigKey otherNamespace = new ConfigKey("tenant-b", "orders", "app.yml");
        apply("scope-1", stablePublish(selected, 0, "selected"));
        apply("scope-2", stablePublish(otherGroup, 0, "other-group"));
        apply("scope-3", stablePublish(otherNamespace, 0, "other-namespace"));

        WatchBatch watch = stateMachine.watch(ConfigStateCodec.changesWatch(
                groupId,
                0,
                new ConfigWatchSelector("public", "orders", List.of()),
                10,
                ReadOptions.linearizable()));

        assertEquals(3, watch.coveredThrough().value());
        assertEquals(1, watch.events().size());
        assertEquals(selected, ConfigStateCodec.decodeChangeEvent(
                watch.events().getFirst().payload()).configKey());
    }

    @Test
    void snapshotRestoresContentsDecisionsChangeLogAndIdempotency() throws Exception {
        ConfigMutation stable = stablePublish(KEY, 0, "stable");
        StateCommand stableCommand = ConfigStateCodec.mutationCommand(
                groupId, "op-stable", stable);
        apply(stableCommand);
        RolloutRuleDraft rule = new RolloutRuleDraft(
                "tag-rule",
                1,
                1,
                5,
                ConfigContentReference.newContent(),
                "tag-rollout",
                RolloutSelectorType.TAG,
                "region",
                List.of("cn-east"),
                0,
                42,
                RolloutRuleStatus.ACTIVE);
        apply("op-rollout", new ConfigMutation(
                ACTOR,
                KEY,
                1,
                ConfigContentDraft.inline("text", 1, bytes("east")),
                ConfigContentReference.existing(1),
                List.of(rule)));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        stateMachine.writeSnapshot(bytes);
        ConfigStateMachine restored = new ConfigStateMachine(groupId);
        restored.installSnapshot(
                stateMachine.snapshotSchemaVersion(),
                new ByteArrayInputStream(bytes.toByteArray()));

        stateMachine = restored;
        ApplicableRelease release = fetch(KEY, new ConfigClientIdentity(
                "client-east", "demo", "10.0.0.1", Map.of("region", "cn-east")));
        assertEquals("east", text(release.content().payload()));
        assertEquals(2, release.decisionRevision());
        assertEquals(2, snapshot().eventRevision());

        ApplyResult replay = apply(stableCommand);
        assertEquals(ApplyStatus.UNCHANGED, replay.status());
        assertEquals(2, snapshot().eventRevision());

        WatchBatch watch = stateMachine.watch(ConfigStateCodec.changesWatch(
                groupId,
                0,
                new ConfigWatchSelector(KEY.namespace(), KEY.group(), List.of(KEY)),
                10,
                ReadOptions.linearizable()));
        assertEquals(2, watch.events().size());
        assertEquals(2, watch.coveredThrough().value());
    }

    @Test
    void rejectsInlineContentWhenDeclaredHashDoesNotMatch() throws Exception {
        ConfigContentDraft content = new ConfigContentDraft(
                "text",
                1,
                bytes("actual"),
                "0".repeat(64),
                "");
        ConfigMutation mutation = new ConfigMutation(
                ACTOR,
                KEY,
                0,
                content,
                ConfigContentReference.newContent(),
                List.of());

        ApplyResult result = apply("bad-hash", mutation);
        assertEquals(ApplyStatus.REJECTED, result.status());
        assertEquals("INVALID_CONTENT",
                ConfigStateCodec.decodeMutationError(result.payload()).code());
        assertEquals(0, snapshot().eventRevision());
    }

    @Test
    void rejectsOverlappingActiveSelectorsAtSamePriority() throws Exception {
        apply("initial", stablePublish(KEY, 0, "stable"));
        RolloutRuleDraft first = clientRule(
                "rule-a", 10, List.of("client-a", "client-b"));
        RolloutRuleDraft second = clientRule(
                "rule-b", 10, List.of("client-b", "client-c"));
        ConfigMutation mutation = new ConfigMutation(
                ACTOR,
                KEY,
                1,
                ConfigContentDraft.inline("text", 1, bytes("candidate")),
                ConfigContentReference.existing(1),
                List.of(first, second));

        ApplyResult result = apply("conflicting-rules", mutation);

        assertEquals(ApplyStatus.REJECTED, result.status());
        assertEquals("INVALID_RULES",
                ConfigStateCodec.decodeMutationError(result.payload()).code());
        ConfigSnapshot snapshot = snapshot();
        assertEquals(1, snapshot.eventRevision());
        assertEquals(1, snapshot.decisions().getFirst().decisionRevision());
        assertEquals(1, fetch(KEY, identity("client-b")).content().contentRevision());
    }

    private ConfigMutation stablePublish(
            ConfigKey key, long expectedDecisionRevision, String content) {
        return new ConfigMutation(
                ACTOR,
                key,
                expectedDecisionRevision,
                ConfigContentDraft.inline("text", 1, bytes(content)),
                ConfigContentReference.newContent(),
                List.of());
    }

    private RolloutRuleDraft clientRule(
            String ruleId, int priority, List<String> clientInstanceIds) {
        return new RolloutRuleDraft(
                ruleId,
                1,
                1,
                priority,
                ConfigContentReference.newContent(),
                "rollout-conflict",
                RolloutSelectorType.CLIENT_INSTANCE_ID,
                "",
                clientInstanceIds,
                0,
                1,
                RolloutRuleStatus.ACTIVE);
    }

    private ApplyResult apply(String operationId, ConfigMutation mutation) {
        return apply(ConfigStateCodec.mutationCommand(groupId, operationId, mutation));
    }

    private ApplyResult apply(StateCommand command) {
        return stateMachine.apply(command, new ApplyContext(groupId, 1, ++logIndex));
    }

    private ApplicableRelease fetch(ConfigKey key, ConfigClientIdentity identity)
            throws Exception {
        QueryResult result = stateMachine.query(ConfigStateCodec.applicableReleaseQuery(
                groupId,
                new ApplicableReleaseRequest(key, identity),
                ReadOptions.linearizable()));
        return ConfigStateCodec.decodeApplicableRelease(result.payload());
    }

    private ConfigSnapshot snapshot() throws Exception {
        QueryResult result = stateMachine.query(ConfigStateCodec.snapshotQuery(
                groupId,
                new ConfigSnapshotRequest(List.of()),
                ReadOptions.linearizable()));
        return ConfigStateCodec.decodeSnapshot(result.payload());
    }

    private static ConfigClientIdentity identity(String clientInstanceId) {
        return new ConfigClientIdentity(
                clientInstanceId, "demo-app", "127.0.0.1", Map.of());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
