package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigProjectionSnapshot;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.repository.ServiceDefinitionRepository;
import cloud.xuantong.registry.state.ActivateServiceDefinition;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.RegistryStateMachine;
import cloud.xuantong.registry.state.ServiceKey;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateProjectionConsistencyServiceTest {
    @TempDir
    Path tempDirectory;

    private StateGroupId configGroup;
    private StateGroupId registryGroup;
    private ConfigStateMachine configState;
    private RegistryStateMachine registryState;
    private ConfigResource resource;
    private ConfigRelease release;
    private ServiceDefinition serviceDefinition;

    @BeforeEach
    void setUp() throws Exception {
        configGroup = StateGroupId.config("config-test");
        registryGroup = StateGroupId.registry("registry-test");
        configState = new ConfigStateMachine(configGroup);
        registryState = new RegistryStateMachine(registryGroup);

        ConfigKey key = new ConfigKey("public", "DEFAULT_GROUP", "app.yml");
        ConfigMutation publish = new ConfigMutation(
                new ConfigActor("management", "admin"),
                key,
                0,
                ConfigContentDraft.inline(
                        "text", 1, "value".getBytes(StandardCharsets.UTF_8)),
                ConfigContentReference.newContent(),
                List.of());
        configState.apply(
                ConfigStateCodec.mutationCommand(configGroup, "publish-1", publish),
                new ApplyContext(configGroup, 1, 1));
        QueryResult digestResult = configState.query(
                ConfigStateCodec.projectionSnapshotQuery(
                        configGroup, cloud.xuantong.state.api.ReadOptions.linearizable()));
        ConfigProjectionSnapshot digest = ConfigStateCodec.decodeProjectionSnapshot(
                digestResult.payload());

        resource = new ConfigResource();
        resource.setId(1L);
        resource.setNamespaceId("public");
        resource.setGroupName("DEFAULT_GROUP");
        resource.setDataId("app.yml");
        resource.setRevision(1L);
        resource.setLifecycleStatus("ACTIVE");

        release = new ConfigRelease();
        release.setReleaseId("release-1");
        release.setConfigId(1L);
        release.setDecisionRevision(1L);
        release.setContentRevision(1L);
        release.setChecksum(digest.entries().getFirst()
                .referencedContents().getFirst().contentHash());
        release.setContentType("text");
        release.setReleaseType("FULL");

        ServiceKey serviceKey = new ServiceKey(
                "public", "DEFAULT_GROUP", "orders");
        registryState.apply(
                RegistryStateCodec.mutationCommand(
                        registryGroup,
                        "activate-orders",
                        new ActivateServiceDefinition(
                                RegistryActor.system("management"),
                                serviceKey,
                                0,
                                1_000)),
                new ApplyContext(registryGroup, 1, 1));

        serviceDefinition = new ServiceDefinition();
        serviceDefinition.setId(1L);
        serviceDefinition.setNamespaceId("public");
        serviceDefinition.setGroupName("DEFAULT_GROUP");
        serviceDefinition.setServiceName("orders");
        serviceDefinition.setServiceGeneration(1L);
        serviceDefinition.setLifecycleState("ACTIVE");
    }

    @Test
    void acceptsMatchingLinearizableStateAndSqlProjection() {
        StateProjectionConsistencyService.ConsistencyReport report = service().check();

        assertTrue(report.consistent());
        assertTrue(report.complete());
        assertTrue(report.issues().isEmpty());
        assertEquals(1, report.config().stateDecisionCount());
        assertEquals(1, report.registry().stateServiceCount());
    }

    @Test
    void reportsRevisionAndGenerationMismatchWithoutMutatingEitherStore() {
        resource.setRevision(0L);
        serviceDefinition.setServiceGeneration(2L);

        StateProjectionConsistencyService.ConsistencyReport report = service().check();

        assertFalse(report.consistent());
        assertTrue(report.issues().stream().anyMatch(issue ->
                "CONFIG_SQL_REVISION_MISMATCH".equals(issue.code())));
        assertTrue(report.issues().stream().anyMatch(issue ->
                "REGISTRY_SQL_GENERATION_MISMATCH".equals(issue.code())));
        assertEquals(0L, resource.getRevision());
        assertEquals(2L, serviceDefinition.getServiceGeneration());
    }

    private StateProjectionConsistencyService service() {
        ConfigStatePlaneProperties configProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                configGroup.value(),
                "state-1@127.0.0.1:19091",
                tempDirectory,
                true);
        RegistryStatePlaneProperties registryProperties =
                new RegistryStatePlaneProperties(
                        true, registryGroup.value(), 1_000, 60_000);
        return new StateProjectionConsistencyService(
                new DirectStateClient(),
                configProperties,
                registryProperties,
                configResourceRepository(),
                configReleaseRepository(),
                emptyRepository(ConfigRolloutRepository.class),
                serviceDefinitionRepository());
    }

    private ConfigResourceRepository configResourceRepository() {
        return (ConfigResourceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ConfigResourceRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAll" -> List.of(resource);
                    case "find" -> resource;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private ConfigReleaseRepository configReleaseRepository() {
        return (ConfigReleaseRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ConfigReleaseRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByDecisionRevision", "findByReleaseId" -> release;
                    case "findByContentRevision", "findByConfigId" -> List.of(release);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private ServiceDefinitionRepository serviceDefinitionRepository() {
        return (ServiceDefinitionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ServiceDefinitionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAll" -> List.of(serviceDefinition);
                    case "find" -> serviceDefinition;
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private <T> T emptyRepository(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return List.class.isAssignableFrom(type) ? List.of() : null;
        }
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        return 0;
    }

    private final class DirectStateClient implements StateClient {
        @Override
        public CompletionStage<ApplyResult> submit(StateCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<QueryResult> query(StateQuery query) {
            QueryResult result = query.groupId().type() == StateGroupType.CONFIG
                    ? configState.query(query) : registryState.query(query);
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletionStage<WatchBatch> watch(WatchRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
