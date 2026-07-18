package cloud.xuantong.server.state.management;

import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigSnapshot;
import cloud.xuantong.config.state.ConfigSnapshotRequest;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.config.state.ConfigClientIdentity;
import cloud.xuantong.config.state.ConfigReleaseSelector;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.ResolveConfigOperationRequest;
import cloud.xuantong.config.state.ResolvedConfigOperation;
import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.model.ConfigStateOperationStatus;
import cloud.xuantong.config.management.model.RolloutStatus;
import cloud.xuantong.config.management.repository.AuditLogRepository;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.management.repository.ConfigStateOperationRepository;
import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStateManagementServiceTest {
    @Test
    void resolvesDroppedReplyAndReplaysOneCommittedPublish() throws Exception {
        Fixture fixture = fixture("baseline");
        fixture.state.dropReplyAfterNextApply = true;

        ConfigStateWriteResult first = fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "publish-1");

        assertFalse(first.projectionPending());
        assertEquals(1L, first.release().getId());
        assertEquals(1L, first.release().getDecisionRevision());
        assertEquals(1, fixture.state.applyCalls);
        assertEquals(ConfigStateOperationStatus.PROJECTED.name(),
                fixture.operations.only().getStatus());

        fixture.resources.resource.setContent("new draft");
        fixture.resources.resource.setChecksum(checksum("new draft"));
        ConfigStateWriteResult replay = fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "publish-1");

        assertEquals(1, fixture.state.applyCalls);
        assertEquals("baseline", replay.release().getContent());
        assertEquals(1, fixture.releases.items.size());
        assertEquals(1, fixture.audits.items.size());
    }

    @Test
    void repairsProjectionWithoutSubmittingTheRaftCommandAgain() throws Exception {
        Fixture fixture = fixture("baseline");
        fixture.projection.failNext = true;

        ConfigStateWriteResult committed = fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "publish-2");

        assertTrue(committed.projectionPending());
        assertEquals(1, fixture.state.applyCalls);
        assertEquals(ConfigStateOperationStatus.PROJECTION_PENDING.name(),
                fixture.operations.only().getStatus());
        assertEquals(0, fixture.releases.items.size());

        fixture.service.recover(fixture.operations.only());

        assertEquals(1, fixture.state.applyCalls);
        assertEquals(ConfigStateOperationStatus.PROJECTED.name(),
                fixture.operations.only().getStatus());
        assertEquals(1, fixture.releases.items.size());
        assertEquals(1, fixture.audits.items.size());
    }

    @Test
    void promotesGrayCandidateThroughOneAuthoritativeDecisionSequence() throws Exception {
        Fixture fixture = fixture("baseline");
        ConfigStateWriteResult baseline = fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "publish-base");
        assertEquals(1L, baseline.release().getContentRevision());

        fixture.resources.resource.setContent("candidate");
        fixture.resources.resource.setChecksum(checksum("candidate"));
        ConfigStateWriteResult started = fixture.service.startRollout(
                "public",
                "DEFAULT_GROUP",
                "app.yml",
                ConfigRolloutPolicy.percentage(25),
                "admin",
                "rollout-start");

        ReleaseDecision gray = fixture.state.currentDecision(
                new ConfigKey("public", "DEFAULT_GROUP", "app.yml"));
        assertEquals(2L, gray.decisionRevision());
        assertEquals(1L, gray.stableContentRevision());
        assertEquals(1, gray.rules().size());
        assertEquals(2L, gray.rules().getFirst().targetContentRevision());
        assertEquals(RolloutStatus.ACTIVE.name(), started.rollout().getStatus());
        ConfigRolloutPolicy projectedPolicy = ConfigRolloutPolicy.restore(started.rollout());
        for (int i = 0; i < 100; i++) {
            String instanceId = "instance-" + i;
            boolean stateMatched = ConfigReleaseSelector.matches(
                    gray.rules().getFirst(),
                    new ConfigClientIdentity(instanceId, "demo", "127.0.0.1", Map.of()));
            assertEquals(stateMatched, projectedPolicy.matches(
                    started.rollout().getRolloutId(), instanceId, "127.0.0.1"));
        }

        ConfigStateWriteResult promoted = fixture.service.promoteRollout(
                "public",
                "DEFAULT_GROUP",
                "app.yml",
                started.rollout().getRolloutId(),
                "admin",
                "rollout-promote");

        ReleaseDecision stable = fixture.state.currentDecision(
                new ConfigKey("public", "DEFAULT_GROUP", "app.yml"));
        assertEquals(3L, stable.decisionRevision());
        assertEquals(2L, stable.stableContentRevision());
        assertTrue(stable.rules().isEmpty());
        assertEquals(RolloutStatus.PROMOTED.name(), promoted.rollout().getStatus());
        assertEquals(3, fixture.releases.items.size());
        assertEquals(3, fixture.audits.items.size());
        assertEquals(3, fixture.state.applyCalls);
    }

    @Test
    void rejectsReusingOperationIdForAnotherCoordinate() throws Exception {
        Fixture fixture = fixture("baseline");
        fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "same-op");
        fixture.resources.resource.setDataId("other.yml");

        ConfigStateWriteException error = assertThrows(
                ConfigStateWriteException.class,
                () -> fixture.service.publish(
                        "public", "DEFAULT_GROUP", "other.yml", "admin", "same-op"));

        assertTrue(error.getMessage().contains("another Config write"));
        assertEquals(1, fixture.state.applyCalls);
    }

    @Test
    void neverDeletesAResourceThatEnteredRaftReleaseHistory() throws Exception {
        Fixture fixture = fixture("baseline");
        fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "publish-delete-guard");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.assertDraftDeletable(
                        "public", "DEFAULT_GROUP", "app.yml"));

        assertTrue(error.getMessage().contains("cannot be deleted"));
    }

    @Test
    void anotherServerCanFinishTheSameSharedOperation() throws Exception {
        Fixture fixture = fixture("baseline");
        fixture.projection.failNext = true;
        ConfigStateWriteResult firstServer = fixture.service.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "multi-server-op");
        assertTrue(firstServer.projectionPending());

        ConfigStateManagementService secondServer = managementService(
                fixture.resources,
                fixture.releases,
                fixture.rollouts,
                fixture.operations,
                fixture.state,
                fixture.projection);
        ConfigStateWriteResult recovered = secondServer.publish(
                "public", "DEFAULT_GROUP", "app.yml", "admin", "multi-server-op");

        assertFalse(recovered.projectionPending());
        assertEquals(1, fixture.state.applyCalls);
        assertEquals(1, fixture.releases.items.size());
        assertEquals(ConfigStateOperationStatus.PROJECTED.name(),
                fixture.operations.only().getStatus());
    }

    private Fixture fixture(String content) throws Exception {
        ResourceRepository resources = new ResourceRepository(resource(content));
        ReleaseRepository releases = new ReleaseRepository();
        RolloutRepository rollouts = new RolloutRepository();
        OperationRepository operations = new OperationRepository();
        AuditRepository audits = new AuditRepository();
        FakeStateAccess state = new FakeStateAccess();
        FailingProjectionService projection = new FailingProjectionService();
        inject(projection, "resourceRepository", resources);
        inject(projection, "releaseRepository", releases);
        inject(projection, "rolloutRepository", rollouts);
        inject(projection, "auditLogRepository", audits);
        inject(projection, "operationRepository", operations);

        ConfigStateManagementService service = managementService(
                resources, releases, rollouts, operations, state, projection);
        return new Fixture(
                service, resources, releases, rollouts, operations, audits, state, projection);
    }

    private ConfigStateManagementService managementService(
            ResourceRepository resources,
            ReleaseRepository releases,
            RolloutRepository rollouts,
            OperationRepository operations,
            FakeStateAccess state,
            FailingProjectionService projection) throws Exception {
        ConfigStateManagementService service = new ConfigStateManagementService();
        inject(service, "resourceRepository", resources);
        inject(service, "releaseRepository", releases);
        inject(service, "rolloutRepository", rollouts);
        inject(service, "operationRepository", operations);
        inject(service, "stateAccess", state);
        inject(service, "projectionService", projection);
        return service;
    }

    private ConfigResource resource(String content) {
        ConfigResource resource = new ConfigResource();
        resource.setId(1L);
        resource.setNamespaceId("public");
        resource.setGroupName("DEFAULT_GROUP");
        resource.setDataId("app.yml");
        resource.setContent(content);
        resource.setContentType("yaml");
        resource.setChecksum(checksum(content));
        resource.setRevision(0L);
        return resource;
    }

    private static String checksum(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass() == ConfigStateProjectionService.class
                ? ConfigStateProjectionService.class.getDeclaredField(name)
                : target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ConfigStateManagementService service,
            ResourceRepository resources,
            ReleaseRepository releases,
            RolloutRepository rollouts,
            OperationRepository operations,
            AuditRepository audits,
            FakeStateAccess state,
            FailingProjectionService projection) {
    }

    private static final class FailingProjectionService extends ConfigStateProjectionService {
        private boolean failNext;

        @Override
        public void project(
                ConfigStateOperation operation,
                ConfigStateProjectionPlan plan,
                ConfigStateCommit commit) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("simulated projection failure");
            }
            super.project(operation, plan, commit);
        }
    }

    private static final class FakeStateAccess implements ConfigStateAccess {
        private final StateGroupId groupId = StateGroupId.config("config-test");
        private final ConfigStateMachine machine = new ConfigStateMachine(groupId);
        private long logIndex;
        private int applyCalls;
        private boolean dropReplyAfterNextApply;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public StateGroupId groupId() {
            return groupId;
        }

        @Override
        public ReleaseDecision currentDecision(ConfigKey key) {
            try {
                QueryResult result = machine.query(ConfigStateCodec.snapshotQuery(
                        groupId,
                        new ConfigSnapshotRequest(List.of(key)),
                        ReadOptions.linearizable()));
                ConfigSnapshot snapshot = ConfigStateCodec.decodeSnapshot(result.payload());
                return snapshot.decisions().isEmpty() ? null : snapshot.decisions().getFirst();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ApplyResult submit(StateCommand command) {
            applyCalls++;
            ApplyResult result = machine.apply(
                    command, new ApplyContext(groupId, 1, ++logIndex));
            if (dropReplyAfterNextApply) {
                dropReplyAfterNextApply = false;
                throw new IllegalStateException("simulated lost reply");
            }
            return result;
        }

        @Override
        public ResolvedConfigOperation resolve(ConfigActor actor, String operationId) {
            try {
                QueryResult result = machine.query(ConfigStateCodec.resolveOperationQuery(
                        groupId,
                        new ResolveConfigOperationRequest(actor, operationId),
                        ReadOptions.linearizable()));
                return ConfigStateCodec.decodeResolvedOperation(result.payload());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class ResourceRepository implements ConfigResourceRepository {
        private final ConfigResource resource;

        private ResourceRepository(ConfigResource resource) {
            this.resource = resource;
        }

        @Override
        public ConfigResource find(ConfigResourceKey key) {
            return resource.getNamespaceId().equals(key.namespaceId())
                    && resource.getGroupName().equals(key.groupName())
                    && resource.getDataId().equals(key.dataId()) ? resource : null;
        }

        @Override public List<ConfigResource> findByGroup(String namespaceId, String groupName) {
            return List.of(resource);
        }
        @Override public long save(ConfigResource value) { return 1; }
        @Override public long updateDraft(ConfigResource value) { return 1; }
        @Override public long updateRevision(Long id, long expected, String checksum, long next) {
            resource.setRevision(next); return 1;
        }
        @Override public long advanceRevision(Long id, long next) {
            if (resource.getRevision() < next) resource.setRevision(next);
            return 1;
        }
        @Override public long deleteUnpublishedDraft(ConfigResourceKey key) { return 0; }
    }

    private static final class ReleaseRepository implements ConfigReleaseRepository {
        private final List<ConfigRelease> items = new ArrayList<>();

        @Override public long save(ConfigRelease release) {
            release.setId((long) items.size() + 1);
            items.add(release);
            return 1;
        }
        @Override public ConfigRelease findByReleaseId(String releaseId) {
            return items.stream().filter(x -> releaseId.equals(x.getReleaseId()))
                    .findFirst().orElse(null);
        }
        @Override public ConfigRelease findByOperationId(String operationId) {
            return items.stream().filter(x -> operationId.equals(x.getOperationId()))
                    .findFirst().orElse(null);
        }
        @Override public ConfigRelease findLatestStable(Long configId) {
            return items.stream()
                    .filter(x -> configId.equals(x.getConfigId()))
                    .filter(x -> !x.getReleaseType().startsWith("GRAY_"))
                    .max(java.util.Comparator.comparingLong(ConfigRelease::getRevision))
                    .orElse(null);
        }
        @Override public List<ConfigRelease> findByConfigId(Long configId) {
            return items.stream().filter(x -> configId.equals(x.getConfigId())).toList();
        }
    }

    private static final class RolloutRepository implements ConfigRolloutRepository {
        private final Map<String, ConfigRollout> items = new LinkedHashMap<>();

        @Override public long save(ConfigRollout rollout) {
            rollout.setId((long) items.size() + 1);
            items.put(rollout.getRolloutId(), rollout);
            return 1;
        }
        @Override public ConfigRollout findActive(Long configId) {
            return items.values().stream()
                    .filter(x -> configId.equals(x.getConfigId()))
                    .filter(x -> RolloutStatus.ACTIVE.name().equals(x.getStatus()))
                    .findFirst().orElse(null);
        }
        @Override public ConfigRollout findByRolloutId(String rolloutId) {
            return items.get(rolloutId);
        }
        @Override public List<ConfigRollout> findByConfigId(Long configId) {
            return items.values().stream()
                    .filter(x -> configId.equals(x.getConfigId())).toList();
        }
        @Override public long complete(
                String rolloutId, RolloutStatus expected, RolloutStatus status, String operator) {
            return completeProjection(rolloutId, expected, status, operator, null, 0);
        }
        @Override public long completeProjection(
                String rolloutId,
                RolloutStatus expected,
                RolloutStatus status,
                String operator,
                String operationId,
                long decisionRevision) {
            ConfigRollout rollout = items.get(rolloutId);
            if (rollout == null || !expected.name().equals(rollout.getStatus())) return 0;
            rollout.setStatus(status.name());
            rollout.setCompletedBy(operator);
            rollout.setCompletedAt(new Date());
            rollout.setCompleteOperationId(operationId);
            rollout.setDecisionRevision(decisionRevision);
            return 1;
        }
    }

    private static final class AuditRepository implements AuditLogRepository {
        private final List<AuditLog> items = new ArrayList<>();

        @Override public long save(AuditLog audit) { items.add(audit); return 1; }
        @Override public AuditLog findByOperationId(String operationId) {
            return items.stream().filter(x -> operationId.equals(x.getOperationId()))
                    .findFirst().orElse(null);
        }
        @Override public List<AuditLog> findByResource(
                String namespaceId, String groupName, String type, String name) {
            return List.copyOf(items);
        }
        @Override public List<AuditLog> findRecent(int limit) { return List.copyOf(items); }
    }

    private static final class OperationRepository
            implements ConfigStateOperationRepository {
        private final Map<String, ConfigStateOperation> items = new LinkedHashMap<>();

        @Override public long save(ConfigStateOperation operation) {
            String key = key(operation.getTenant(), operation.getPrincipal(), operation.getOperationId());
            if (items.containsKey(key)) throw new IllegalStateException("duplicate operation");
            operation.setId((long) items.size() + 1);
            items.put(key, operation);
            return 1;
        }
        @Override public ConfigStateOperation find(
                String tenant, String principal, String operationId) {
            return items.get(key(tenant, principal, operationId));
        }
        @Override public ConfigStateOperation findUnfinishedForConfig(
                String namespaceId, String groupName, String dataId) {
            return forConfig(namespaceId, groupName, dataId).stream()
                    .filter(x -> !ConfigStateOperationStatus.PROJECTED.name()
                            .equals(x.getStatus()))
                    .filter(x -> !ConfigStateOperationStatus.FAILED.name()
                            .equals(x.getStatus()))
                    .findFirst().orElse(null);
        }
        @Override public ConfigStateOperation findAnyNonFailedForConfig(
                String namespaceId, String groupName, String dataId) {
            return forConfig(namespaceId, groupName, dataId).stream()
                    .filter(x -> !ConfigStateOperationStatus.FAILED.name()
                            .equals(x.getStatus()))
                    .findFirst().orElse(null);
        }
        @Override public List<ConfigStateOperation> findRecoverable(int limit) {
            return items.values().stream()
                    .filter(x -> !ConfigStateOperationStatus.PROJECTED.name().equals(x.getStatus()))
                    .filter(x -> !ConfigStateOperationStatus.FAILED.name().equals(x.getStatus()))
                    .limit(limit).toList();
        }
        @Override public long markCommitted(
                Long id, long content, long decision, long event) {
            ConfigStateOperation operation = byId(id);
            operation.setStatus(ConfigStateOperationStatus.COMMITTED.name());
            operation.setContentRevision(content);
            operation.setDecisionRevision(decision);
            operation.setEventRevision(event);
            return 1;
        }
        @Override public long markProjectionPending(Long id, String error) {
            ConfigStateOperation operation = byId(id);
            if (ConfigStateOperationStatus.PROJECTED.name().equals(operation.getStatus())) return 0;
            operation.setStatus(ConfigStateOperationStatus.PROJECTION_PENDING.name());
            operation.setErrorMessage(error);
            return 1;
        }
        @Override public long markProjected(Long id) {
            byId(id).setStatus(ConfigStateOperationStatus.PROJECTED.name()); return 1;
        }
        @Override public long markFailed(Long id, String error) {
            ConfigStateOperation operation = byId(id);
            operation.setStatus(ConfigStateOperationStatus.FAILED.name());
            operation.setErrorMessage(error);
            return 1;
        }
        @Override public long updatePendingError(Long id, String error) {
            byId(id).setErrorMessage(error); return 1;
        }
        @Override public long updateStatus(
                Long id, ConfigStateOperationStatus expected, ConfigStateOperationStatus status) {
            ConfigStateOperation operation = byId(id);
            if (!expected.name().equals(operation.getStatus())) return 0;
            operation.setStatus(status.name()); return 1;
        }
        private ConfigStateOperation only() {
            assertEquals(1, items.size());
            return items.values().iterator().next();
        }
        private ConfigStateOperation byId(Long id) {
            return items.values().stream().filter(x -> id.equals(x.getId()))
                    .findFirst().orElseThrow();
        }
        private List<ConfigStateOperation> forConfig(
                String namespaceId, String groupName, String dataId) {
            return items.values().stream()
                    .filter(x -> namespaceId.equals(x.getNamespaceId()))
                    .filter(x -> groupName.equals(x.getGroupName()))
                    .filter(x -> dataId.equals(x.getDataId()))
                    .toList();
        }
        private static String key(String tenant, String principal, String operationId) {
            return tenant + "\0" + principal + "\0" + operationId;
        }
    }
}
