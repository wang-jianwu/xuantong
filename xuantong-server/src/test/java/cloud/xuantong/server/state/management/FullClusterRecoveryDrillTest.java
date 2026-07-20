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
import cloud.xuantong.config.state.ConfigProjectionEntry;
import cloud.xuantong.config.state.ConfigProjectionSnapshot;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.repository.ServiceDefinitionRepository;
import cloud.xuantong.raft.ratis.RatisGroupCatalog;
import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisNodeOptions;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
import cloud.xuantong.raft.ratis.RatisStateNode;
import cloud.xuantong.raft.ratis.RatisStateRouter;
import cloud.xuantong.registry.state.ActivateServiceDefinition;
import cloud.xuantong.registry.state.DeleteServiceDefinition;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.RegistryStateMachine;
import cloud.xuantong.registry.state.ServiceLifecycleSnapshot;
import cloud.xuantong.registry.state.ServiceLifecycleStatus;
import cloud.xuantong.server.config.TestSchemaMigration;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in disaster-recovery drill that crosses the H2 or MySQL management
 * database and both real Apache Ratis State Groups.
 *
 * <p>The test first repairs an unwanted Config publish and service deletion
 * through new authoritative State mutations. It then backs up that recovered
 * state, destroys every database and voter directory, and proves that two
 * independent node archives recover quorum, the restored SQL projection agrees
 * with linearizable State, an empty third voter catches up, and new writes can
 * still be committed and projected.</p>
 */
class FullClusterRecoveryDrillTest {
    private static final String NAMESPACE = "public";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String DATA_ID = "recovery.yml";
    private static final String SERVICE = "orders";
    private static final String DATABASE_PREFIX = "xuantong_restore_drill_";

    @TempDir
    Path tempDirectory;

    private final Map<String, RatisStateNode> openNodes = new LinkedHashMap<>();
    private final List<Integer> allocatedPorts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        closeAllNodes();
    }

    @Test
    void restoresH2AndBothStateGroupsThenContinuesLinearizableWrites()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("xuantong.recovery.drill"),
                "Enable with -Dxuantong.recovery.drill=true");
        runRecoveryDrill(new H2RecoveryDatabase());
    }

    @Test
    void restoresMySqlAndBothStateGroupsThenContinuesLinearizableWrites()
            throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("xuantong.recovery.drill"),
                "Enable with -Dxuantong.recovery.drill=true");
        MySqlRecoveryDatabase database = new MySqlRecoveryDatabase(
                MySqlServer.fromEnvironment());
        Assumptions.assumeTrue(database.configured(),
                "Configure XUANTONG_RECOVERY_MYSQL_HOST to run the combined drill");
        runRecoveryDrill(database);
    }

    private void runRecoveryDrill(RecoveryDatabase recoveryDatabase)
            throws Exception {
        Throwable primaryFailure = null;
        try {
            recoveryDatabase.initialize();
            executeRecoveryDrill(recoveryDatabase);
        } catch (Exception | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            try {
                recoveryDatabase.close();
            } catch (Exception cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private void executeRecoveryDrill(RecoveryDatabase recoveryDatabase)
            throws Exception {

        Path repository = repositoryRoot();
        List<RatisPeerDefinition> peers = List.of(
                peer("state-1"), peer("state-2"), peer("state-3"));
        RatisGroupDefinition configGroup = new RatisGroupDefinition(
                StateGroupId.config("recovery-config"), peers);
        RatisGroupDefinition registryGroup = new RatisGroupDefinition(
                StateGroupId.registry("recovery-registry"), peers);
        RatisGroupCatalog catalog = RatisGroupCatalog.compact(
                configGroup, registryGroup);

        Path databaseBackup = tempDirectory.resolve(
                "backup/management" + recoveryDatabase.backupSuffix());
        Path archiveOne = tempDirectory.resolve("backup/state-1.tar.gz");
        Path archiveTwo = tempDirectory.resolve("backup/state-2.tar.gz");

        startNodes(catalog, peers);
        installAdditionalGroups(catalog, peers);
        try (RatisStateRouter router = router(catalog);
             HikariDataSource database = recoveryDatabase.openActive()) {
            ConfigProjectionEntry initialConfig = publish(
                    router, configGroup.groupId(), "publish-known-good", 0,
                    "version: known-good\n");
            activateService(
                    router, registryGroup.groupId(), "activate-orders", 0L);
            insertConfigProjection(
                    database, initialConfig, "version: known-good\n",
                    "release-known-good", "publish-known-good");
            insertServiceProjection(database, 1L);
            assertConsistent(router, configGroup.groupId(), registryGroup.groupId(),
                    database);

            ConfigProjectionEntry unwantedConfig = publish(
                    router, configGroup.groupId(), "publish-unwanted", 1,
                    "version: unwanted\n");
            deleteService(router, registryGroup.groupId(), "delete-orders", 1L);
            replaceConfigProjection(
                    database, unwantedConfig, "version: unwanted\n",
                    "release-unwanted", "publish-unwanted");
            deleteServiceProjection(database);
            assertConsistent(router, configGroup.groupId(), registryGroup.groupId(),
                    database);
            assertEquals(2L, onlyConfig(router, configGroup.groupId()).decisionRevision());
            assertEquals(ServiceLifecycleStatus.DELETED,
                    onlyService(router, registryGroup.groupId()).status());

            ConfigProjectionEntry logicalRollback = rollbackConfig(
                    router, configGroup.groupId(), "rollback-unwanted", 2, 1);
            activateService(
                    router, registryGroup.groupId(), "reactivate-orders", 1L);
            replaceConfigProjection(
                    database, logicalRollback, "version: known-good\n",
                    "release-logical-rollback", "rollback-unwanted");
            insertServiceProjection(database, 2L);
            assertConsistent(router, configGroup.groupId(), registryGroup.groupId(),
                    database);
            assertEquals(3L, onlyConfig(router, configGroup.groupId()).decisionRevision());
            assertEquals(1L, onlyConfig(router, configGroup.groupId())
                    .stableContentRevision());
            assertEquals(2L, onlyService(
                    router, registryGroup.groupId()).generation());

            forceSnapshots(configGroup, registryGroup, List.of("state-1", "state-2"));
        }
        closeAllNodes();

        recoveryDatabase.dump(databaseBackup);
        backupNode(repository, "state-1", databaseBackup, archiveOne);
        backupNode(repository, "state-2", databaseBackup, archiveTwo);

        for (RatisPeerDefinition peer : peers) {
            deleteTree(storage(peer.nodeId()));
        }
        recoveryDatabase.destroyActive();

        Path restoredDatabaseOne = tempDirectory.resolve(
                "restore/state-1-management" + recoveryDatabase.backupSuffix());
        Path restoredDatabaseTwo = tempDirectory.resolve(
                "restore/state-2-management" + recoveryDatabase.backupSuffix());
        restoreNode(repository, archiveOne, "state-1", restoredDatabaseOne);
        restoreNode(repository, archiveTwo, "state-2", restoredDatabaseTwo);
        assertEquals(-1L, Files.mismatch(restoredDatabaseOne, restoredDatabaseTwo));
        recoveryDatabase.restore(restoredDatabaseOne);

        List<RatisPeerDefinition> restoredQuorum = peers.subList(0, 2);
        startNodes(catalog, restoredQuorum);
        installAdditionalGroups(catalog, restoredQuorum);
        try (RatisStateRouter router = router(catalog);
             HikariDataSource database = recoveryDatabase.openRestored()) {
            assertEquals(3L, onlyConfig(router, configGroup.groupId()).decisionRevision());
            assertEquals(1L, onlyConfig(
                    router, configGroup.groupId()).stableContentRevision());
            assertEquals(hash("version: known-good\n"), onlyConfig(
                    router, configGroup.groupId()).referencedContents().getFirst().contentHash());
            assertEquals(ServiceLifecycleStatus.ACTIVE,
                    onlyService(router, registryGroup.groupId()).status());
            assertEquals(2L, onlyService(
                    router, registryGroup.groupId()).generation());
            assertConsistent(router, configGroup.groupId(), registryGroup.groupId(),
                    database);

            startNodes(catalog, List.of(peers.get(2)));
            installAdditionalGroups(catalog, List.of(peers.get(2)));

            ConfigProjectionEntry recoveredWrite = publish(
                    router, configGroup.groupId(), "publish-after-recovery", 3,
                    "version: after-recovery\n");
            ApplyResult serviceDelete = deleteService(
                    router, registryGroup.groupId(), "delete-after-recovery", 2L);
            replaceConfigProjection(
                    database, recoveredWrite, "version: after-recovery\n",
                    "release-after-recovery", "publish-after-recovery");
            deleteServiceProjection(database);

            waitForAppliedOnAllNodes(
                    peers, configGroup, recoveredWriteAppliedIndex(router, configGroup.groupId()),
                    Duration.ofSeconds(20));
            waitForAppliedOnAllNodes(
                    peers, registryGroup, serviceDelete.appliedIndex(),
                    Duration.ofSeconds(20));
            assertConsistent(router, configGroup.groupId(), registryGroup.groupId(),
                    database);
            assertEquals(4L, onlyConfig(router, configGroup.groupId()).decisionRevision());
            assertEquals(hash("version: after-recovery\n"), onlyConfig(
                    router, configGroup.groupId()).referencedContents().getLast().contentHash());
            assertEquals(ServiceLifecycleStatus.DELETED,
                    onlyService(router, registryGroup.groupId()).status());
        }
    }

    private void backupNode(
            Path repository,
            String nodeId,
            Path databaseBackup,
            Path archive) throws Exception {
        Path snapshotResult = tempDirectory.resolve("backup/" + nodeId + "-snapshot.json");
        Files.createDirectories(snapshotResult.getParent());
        Files.writeString(snapshotResult,
                "{\"targetNodeId\":\"" + nodeId + "\",\"groups\":["
                        + "{\"groupType\":\"CONFIG\"},"
                        + "{\"groupType\":\"REGISTRY\"}]}\n");
        run(repository.resolve("scripts/backup-xuantong-node.sh"),
                "--state-dir", storage(nodeId).toString(),
                "--database-backup", databaseBackup.toString(),
                "--snapshot-result", snapshotResult.toString(),
                "--node-id", nodeId,
                "--output", archive.toString(),
                "--offline-confirmed",
                "--expected-groups", "2");
    }

    private void restoreNode(
            Path repository,
            Path archive,
            String nodeId,
            Path databaseOutput) throws Exception {
        run(repository.resolve("scripts/restore-xuantong-node.sh"),
                "--archive", archive.toString(),
                "--state-dir", storage(nodeId).toString(),
                "--database-output", databaseOutput.toString(),
                "--expected-node-id", nodeId,
                "--offline-confirmed",
                "--confirm-restore");
    }

    private void forceSnapshots(
            RatisGroupDefinition configGroup,
            RatisGroupDefinition registryGroup,
            List<String> nodeIds) throws Exception {
        for (RatisGroupDefinition group : List.of(configGroup, registryGroup)) {
            try (cloud.xuantong.raft.ratis.RatisStateClient client =
                         new cloud.xuantong.raft.ratis.RatisStateClient(
                                 group, Duration.ofSeconds(3), 10)) {
                for (String nodeId : nodeIds) {
                    client.forceSnapshot(Duration.ofSeconds(10), nodeId);
                }
            }
        }
    }

    private ConfigProjectionEntry publish(
            RatisStateRouter router,
            StateGroupId groupId,
            String operationId,
            long expectedDecisionRevision,
            String content) throws Exception {
        ConfigMutation mutation = new ConfigMutation(
                new ConfigActor("recovery-drill", "operator"),
                new ConfigKey(NAMESPACE, GROUP, DATA_ID),
                expectedDecisionRevision,
                ConfigContentDraft.inline(
                        "yaml", 1, content.getBytes(StandardCharsets.UTF_8)),
                ConfigContentReference.newContent(),
                List.of());
        ApplyResult result = submitEventually(router,
                ConfigStateCodec.mutationCommand(groupId, operationId, mutation));
        assertEquals(ApplyStatus.APPLIED, result.status());
        return onlyConfig(router, groupId);
    }

    private ConfigProjectionEntry rollbackConfig(
            RatisStateRouter router,
            StateGroupId groupId,
            String operationId,
            long expectedDecisionRevision,
            long targetContentRevision) throws Exception {
        ConfigMutation mutation = new ConfigMutation(
                new ConfigActor("recovery-drill", "operator"),
                new ConfigKey(NAMESPACE, GROUP, DATA_ID),
                expectedDecisionRevision,
                null,
                ConfigContentReference.existing(targetContentRevision),
                List.of());
        ApplyResult result = submitEventually(router,
                ConfigStateCodec.mutationCommand(groupId, operationId, mutation));
        assertEquals(ApplyStatus.APPLIED, result.status());
        return onlyConfig(router, groupId);
    }

    private void activateService(
            RatisStateRouter router,
            StateGroupId groupId,
            String operationId,
            long expectedPreviousGeneration) throws Exception {
        ApplyResult result = submitEventually(router,
                RegistryStateCodec.mutationCommand(
                        groupId,
                        operationId,
                        new ActivateServiceDefinition(
                                RegistryActor.system("recovery-drill"),
                                new cloud.xuantong.registry.state.ServiceKey(
                                        NAMESPACE, GROUP, SERVICE),
                                expectedPreviousGeneration,
                                System.currentTimeMillis())));
        assertEquals(ApplyStatus.APPLIED, result.status());
    }

    private ApplyResult deleteService(
            RatisStateRouter router,
            StateGroupId groupId,
            String operationId,
            long expectedGeneration) throws Exception {
        ApplyResult result = submitEventually(router,
                RegistryStateCodec.mutationCommand(
                        groupId,
                        operationId,
                        new DeleteServiceDefinition(
                                RegistryActor.system("recovery-drill"),
                                new cloud.xuantong.registry.state.ServiceKey(
                                        NAMESPACE, GROUP, SERVICE),
                                expectedGeneration,
                                System.currentTimeMillis())));
        assertEquals(ApplyStatus.APPLIED, result.status());
        return result;
    }

    private ConfigProjectionEntry onlyConfig(
            RatisStateRouter router, StateGroupId groupId) throws Exception {
        QueryResult result = queryEventually(router,
                ConfigStateCodec.projectionSnapshotQuery(
                        groupId, ReadOptions.linearizable()));
        ConfigProjectionSnapshot snapshot = ConfigStateCodec.decodeProjectionSnapshot(
                result.payload());
        assertEquals(1, snapshot.entries().size());
        return snapshot.entries().getFirst();
    }

    private cloud.xuantong.registry.state.ServiceLifecycle onlyService(
            RatisStateRouter router, StateGroupId groupId) throws Exception {
        QueryResult result = queryEventually(router,
                RegistryStateCodec.serviceLifecycleSnapshotQuery(
                        groupId, ReadOptions.linearizable()));
        ServiceLifecycleSnapshot snapshot =
                RegistryStateCodec.decodeServiceLifecycleSnapshot(result.payload());
        assertEquals(1, snapshot.services().size());
        return snapshot.services().getFirst();
    }

    private long recoveredWriteAppliedIndex(
            RatisStateRouter router, StateGroupId groupId) throws Exception {
        return queryEventually(router,
                ConfigStateCodec.projectionSnapshotQuery(
                        groupId, ReadOptions.linearizable())).appliedIndex();
    }

    private ApplyResult submitEventually(
            RatisStateRouter router,
            cloud.xuantong.state.api.StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return router.submit(command).toCompletableFuture()
                        .get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("State write did not complete") : last;
    }

    private QueryResult queryEventually(
            RatisStateRouter router,
            cloud.xuantong.state.api.StateQuery query) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return router.query(query).toCompletableFuture()
                        .get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("State query did not complete") : last;
    }

    private void assertConsistent(
            RatisStateRouter router,
            StateGroupId configGroup,
            StateGroupId registryGroup,
            HikariDataSource database) {
        JdbcRepositories repositories = new JdbcRepositories(database);
        ConfigStatePlaneProperties configProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                configGroup.value(),
                "state-1@127.0.0.1:1",
                tempDirectory,
                true);
        RegistryStatePlaneProperties registryProperties =
                new RegistryStatePlaneProperties(
                        true, registryGroup.value(), 1_000, 60_000);
        StateProjectionConsistencyService.ConsistencyReport report =
                new StateProjectionConsistencyService(
                        router,
                        configProperties,
                        registryProperties,
                        repositories.configResources(),
                        repositories.configReleases(),
                        repositories.configRollouts(),
                        repositories.serviceDefinitions())
                        .check();
        assertTrue(report.consistent(), () -> "Recovery consistency issues: "
                + report.issues());
        assertTrue(report.complete());
        assertFalse(report.issuesTruncated());
    }

    private void insertConfigProjection(
            HikariDataSource database,
            ConfigProjectionEntry entry,
            String content,
            String releaseId,
            String operationId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement resource = connection.prepareStatement("""
                    INSERT INTO config_resource (
                        id, namespace_id, group_name, data_id, content, content_type,
                        checksum, revision, draft_revision, lifecycle_status,
                        created_by, updated_by)
                    VALUES (1, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 'drill', 'drill')
                    """);
                 PreparedStatement release = connection.prepareStatement("""
                    INSERT INTO config_release (
                        id, release_id, config_id, namespace_id, group_name, data_id,
                        revision, content_revision, decision_revision, event_revision,
                        content, content_type, checksum, release_type, operator, operation_id)
                    VALUES (100, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FULL', 'drill', ?)
                    """)) {
                var digest = entry.referencedContents().getFirst();
                resource.setString(1, NAMESPACE);
                resource.setString(2, GROUP);
                resource.setString(3, DATA_ID);
                resource.setString(4, content);
                resource.setString(5, digest.contentType());
                resource.setString(6, digest.contentHash());
                resource.setLong(7, entry.decisionRevision());
                resource.executeUpdate();

                release.setString(1, releaseId);
                release.setString(2, NAMESPACE);
                release.setString(3, GROUP);
                release.setString(4, DATA_ID);
                release.setLong(5, entry.decisionRevision());
                release.setLong(6, digest.contentRevision());
                release.setLong(7, entry.decisionRevision());
                release.setLong(8, entry.decisionRevision());
                release.setString(9, content);
                release.setString(10, digest.contentType());
                release.setString(11, digest.contentHash());
                release.setString(12, operationId);
                release.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void replaceConfigProjection(
            HikariDataSource database,
            ConfigProjectionEntry entry,
            String content,
            String releaseId,
            String operationId) throws SQLException {
        var digest = entry.referencedContents().stream()
                .filter(value -> value.contentRevision()
                        == entry.stableContentRevision())
                .findFirst()
                .orElseThrow();
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement resource = connection.prepareStatement("""
                    UPDATE config_resource
                    SET content = ?, content_type = ?, checksum = ?, revision = ?,
                        lifecycle_status = 'ACTIVE', updated_by = 'drill',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = 1
                    """);
                 PreparedStatement release = connection.prepareStatement("""
                    INSERT INTO config_release (
                        release_id, config_id, namespace_id, group_name, data_id,
                        revision, content_revision, decision_revision, event_revision,
                        content, content_type, checksum, release_type, operator, operation_id)
                    VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FULL', 'drill', ?)
                    """)) {
                resource.setString(1, content);
                resource.setString(2, digest.contentType());
                resource.setString(3, digest.contentHash());
                resource.setLong(4, entry.decisionRevision());
                assertEquals(1, resource.executeUpdate());

                release.setString(1, releaseId);
                release.setString(2, NAMESPACE);
                release.setString(3, GROUP);
                release.setString(4, DATA_ID);
                release.setLong(5, entry.decisionRevision());
                release.setLong(6, digest.contentRevision());
                release.setLong(7, entry.decisionRevision());
                release.setLong(8, entry.decisionRevision());
                release.setString(9, content);
                release.setString(10, digest.contentType());
                release.setString(11, digest.contentHash());
                release.setString(12, operationId);
                release.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void insertServiceProjection(
            HikariDataSource database, long generation) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO service_definition (
                         id, namespace_id, group_name, service_name,
                         service_generation, lifecycle_state, created_by)
                     VALUES (1, ?, ?, ?, ?, 'ACTIVE', 'drill')
                     """)) {
            statement.setString(1, NAMESPACE);
            statement.setString(2, GROUP);
            statement.setString(3, SERVICE);
            statement.setLong(4, generation);
            statement.executeUpdate();
        }
    }

    private void deleteServiceProjection(HikariDataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM service_definition
                     WHERE namespace_id = ? AND group_name = ? AND service_name = ?
                     """)) {
            statement.setString(1, NAMESPACE);
            statement.setString(2, GROUP);
            statement.setString(3, SERVICE);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private HikariDataSource h2Database(Path databaseBase, boolean migrate) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + databaseBase.toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        HikariDataSource dataSource = new HikariDataSource(config);
        if (migrate) {
            try {
                TestSchemaMigration.migrateH2(dataSource);
            } catch (Exception e) {
                dataSource.close();
                throw new IllegalStateException("H2 migration failed", e);
            }
        }
        return dataSource;
    }

    private HikariDataSource mySqlDatabase(
            MySqlServer server, String database, boolean migrate) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(server.jdbcUrl(database));
        config.setUsername(server.user());
        config.setPassword(server.password());
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        HikariDataSource dataSource = new HikariDataSource(config);
        if (migrate) {
            try {
                TestSchemaMigration.migrateMySql(dataSource);
            } catch (Exception e) {
                dataSource.close();
                throw new IllegalStateException("MySQL migration failed", e);
            }
        }
        return dataSource;
    }

    private interface RecoveryDatabase extends AutoCloseable {
        void initialize() throws Exception;

        HikariDataSource openActive();

        void dump(Path output) throws Exception;

        void destroyActive() throws Exception;

        void restore(Path input) throws Exception;

        HikariDataSource openRestored();

        String backupSuffix();

        @Override
        void close() throws Exception;
    }

    private final class H2RecoveryDatabase implements RecoveryDatabase {
        private final Path databaseBase = tempDirectory.resolve("database/xuantong");
        private final Path databaseFile = Path.of(databaseBase + ".mv.db");

        @Override
        public void initialize() throws IOException {
            Files.createDirectories(databaseBase.getParent());
        }

        @Override
        public HikariDataSource openActive() {
            return h2Database(databaseBase, true);
        }

        @Override
        public void dump(Path output) throws Exception {
            run(repositoryRoot().resolve("scripts/dump-xuantong-database.sh"),
                    "--dialect", "h2",
                    "--h2-file", databaseFile.toString(),
                    "--output", output.toString(),
                    "--offline-confirmed");
        }

        @Override
        public void destroyActive() throws IOException {
            Files.delete(databaseFile);
        }

        @Override
        public void restore(Path input) throws Exception {
            run(repositoryRoot().resolve("scripts/import-xuantong-database.sh"),
                    "--dialect", "h2",
                    "--input", input.toString(),
                    "--h2-file", databaseFile.toString(),
                    "--offline-confirmed",
                    "--confirm-restore");
        }

        @Override
        public HikariDataSource openRestored() {
            return h2Database(databaseBase, true);
        }

        @Override
        public String backupSuffix() {
            return ".mv.db";
        }

        @Override
        public void close() {
            // JUnit owns the isolated temporary directory.
        }
    }

    private final class MySqlRecoveryDatabase implements RecoveryDatabase {
        private final MySqlServer server;
        private final String sourceDatabase;
        private final String targetDatabase;
        private boolean sourceCreated;
        private boolean targetCreated;

        private MySqlRecoveryDatabase(MySqlServer server) {
            this.server = server;
            String suffix = UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 12);
            this.sourceDatabase = DATABASE_PREFIX + "full_src_" + suffix;
            this.targetDatabase = DATABASE_PREFIX + "full_dst_" + suffix;
        }

        private boolean configured() {
            return server.configured();
        }

        @Override
        public void initialize() throws Exception {
            requireMySqlClientCommands();
            createMySqlDatabase(server, sourceDatabase);
            sourceCreated = true;
        }

        @Override
        public HikariDataSource openActive() {
            return mySqlDatabase(server, sourceDatabase, true);
        }

        @Override
        public void dump(Path output) throws Exception {
            run(databaseCommand(
                            "scripts/dump-xuantong-database.sh",
                            "--dialect", "mysql",
                            "--host", server.host(),
                            "--port", Integer.toString(server.port()),
                            "--database", sourceDatabase,
                            "--user", server.user(),
                            "--output", output.toString()),
                    Map.of("XUANTONG_DB_PASSWORD", server.password()));
        }

        @Override
        public void destroyActive() {
            dropMySqlDatabase(server, sourceDatabase);
            sourceCreated = false;
        }

        @Override
        public void restore(Path input) throws Exception {
            createMySqlDatabase(server, targetDatabase);
            targetCreated = true;
            run(databaseCommand(
                            "scripts/import-xuantong-database.sh",
                            "--dialect", "mysql",
                            "--input", input.toString(),
                            "--host", server.host(),
                            "--port", Integer.toString(server.port()),
                            "--database", targetDatabase,
                            "--user", server.user(),
                            "--target-empty-confirmed",
                            "--confirm-restore"),
                    Map.of("XUANTONG_DB_PASSWORD", server.password()));
        }

        @Override
        public HikariDataSource openRestored() {
            return mySqlDatabase(server, targetDatabase, true);
        }

        @Override
        public String backupSuffix() {
            return ".mysql.sql";
        }

        @Override
        public void close() {
            RuntimeException cleanupFailure = null;
            if (targetCreated) {
                cleanupFailure = cleanupMySqlDatabase(
                        server, targetDatabase, cleanupFailure);
                targetCreated = false;
            }
            if (sourceCreated) {
                cleanupFailure = cleanupMySqlDatabase(
                        server, sourceDatabase, cleanupFailure);
                sourceCreated = false;
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private List<String> databaseCommand(String script, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(repositoryRoot().resolve(script).toString());
        command.addAll(List.of(arguments));
        return command;
    }

    private void createMySqlDatabase(MySqlServer server, String database)
            throws SQLException {
        requireSafeDatabaseName(database);
        try (Connection connection = java.sql.DriverManager.getConnection(
                server.jdbcUrl("mysql"), server.user(), server.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4");
        }
    }

    private RuntimeException cleanupMySqlDatabase(
            MySqlServer server,
            String database,
            RuntimeException previousFailure) {
        try {
            dropMySqlDatabase(server, database);
            return previousFailure;
        } catch (RuntimeException e) {
            if (previousFailure == null) {
                return e;
            }
            previousFailure.addSuppressed(e);
            return previousFailure;
        }
    }

    private void dropMySqlDatabase(MySqlServer server, String database) {
        requireSafeDatabaseName(database);
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection connection = java.sql.DriverManager.getConnection(
                    server.jdbcUrl("mysql"), server.user(), server.password());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + database + "`");
                return;
            } catch (SQLException e) {
                lastFailure = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(attempt * 500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        e.addSuppressed(interrupted);
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Failed to remove recovery drill database " + database,
                lastFailure);
    }

    private void requireMySqlClientCommands() throws Exception {
        for (String command : List.of("mysql", "mysqldump")) {
            Process process = new ProcessBuilder(
                    "sh", "-c", "command -v " + command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(),
                    () -> command + " is required for the recovery drill: " + output);
        }
    }

    private static void requireSafeDatabaseName(String database) {
        if (database == null
                || !database.startsWith(DATABASE_PREFIX)
                || !database.matches("[a-z0-9_]{20,63}")) {
            throw new IllegalArgumentException(
                    "Unsafe recovery drill database name: " + database);
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record MySqlServer(
            String host,
            int port,
            String user,
            String password) {

        private static MySqlServer fromEnvironment() {
            String portValue = environment(
                    "XUANTONG_RECOVERY_MYSQL_PORT", "3306");
            int port;
            try {
                port = Integer.parseInt(portValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid recovery database port: " + portValue, e);
            }
            return new MySqlServer(
                    environment("XUANTONG_RECOVERY_MYSQL_HOST", ""),
                    port,
                    environment("XUANTONG_RECOVERY_MYSQL_USER", "root"),
                    environment("XUANTONG_RECOVERY_MYSQL_PASSWORD", ""));
        }

        private boolean configured() {
            return host != null && !host.isBlank();
        }

        private String jdbcUrl(String database) {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&connectTimeout=10000"
                    + "&socketTimeout=30000&tcpKeepAlive=true";
        }
    }

    private void startNodes(
            RatisGroupCatalog catalog,
            List<RatisPeerDefinition> startingPeers) throws Exception {
        for (RatisPeerDefinition peer : startingPeers) {
            if (openNodes.containsKey(peer.nodeId())) {
                continue;
            }
            RatisNodeOptions options = new RatisNodeOptions(
                    peer.nodeId(),
                    catalog.bootstrapGroup(),
                    storage(peer.nodeId()),
                    Duration.ofMillis(300),
                    Duration.ofMillis(600),
                    Duration.ofSeconds(3),
                    20_000,
                    false);
            RatisStateNode node = new RatisStateNode(
                    options,
                    catalog,
                    groupId -> groupId.type() == StateGroupType.CONFIG
                            ? new ConfigStateMachine(groupId)
                            : new RegistryStateMachine(groupId));
            node.start();
            openNodes.put(peer.nodeId(), node);
        }
    }

    private void installAdditionalGroups(
            RatisGroupCatalog catalog,
            List<RatisPeerDefinition> peers) throws Exception {
        for (RatisGroupDefinition group : catalog.groups()) {
            if (group.groupId().equals(catalog.bootstrapGroup().groupId())) {
                continue;
            }
            for (RatisPeerDefinition peer : peers) {
                openNodes.get(peer.nodeId()).addGroup(group);
            }
        }
    }

    private RatisStateRouter router(RatisGroupCatalog catalog) {
        return new RatisStateRouter(catalog.groups(), Duration.ofSeconds(3), 10);
    }

    private void waitForAppliedOnAllNodes(
            List<RatisPeerDefinition> peers,
            RatisGroupDefinition group,
            long requiredIndex,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean complete = true;
            for (RatisPeerDefinition peer : peers) {
                RatisStateNode node = openNodes.get(peer.nodeId());
                long applied = node.server()
                        .getDivision(group.toRaftGroupId())
                        .getInfo()
                        .getLastAppliedIndex();
                if (applied < requiredIndex) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("State Group did not converge after recovery: "
                + group.groupId());
    }

    private void closeAllNodes() {
        List<RatisStateNode> nodes = new ArrayList<>(openNodes.values());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            try {
                nodes.get(i).close();
            } catch (Exception ignored) {
            }
        }
        openNodes.clear();
        awaitAllocatedPortsReleased(Duration.ofSeconds(8));
    }

    private void awaitAllocatedPortsReleased(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Integer> pending = new ArrayList<>(allocatedPorts);
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            pending.removeIf(this::canBind);
            if (!pending.isEmpty()) {
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for Ratis ports", e);
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException(
                    "Ratis test ports were not released: " + pending);
        }
    }

    private boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private RatisPeerDefinition peer(String nodeId) throws Exception {
        int port = freePort();
        allocatedPorts.add(port);
        return new RatisPeerDefinition(nodeId, "127.0.0.1", port);
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable: " + e.getMessage());
            return -1;
        }
    }

    private Path storage(String nodeId) {
        return tempDirectory.resolve("state").resolve(nodeId);
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isExecutable(current.resolve(
                    "scripts/import-xuantong-database.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Xuantong repository root was not found");
    }

    private void run(Path executable, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        run(command, Map.of());
    }

    private void run(List<String> command, Map<String, String> environment)
            throws Exception {
        Path outputFile = Files.createTempFile(
                tempDirectory, "full-recovery-command-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            terminateProcessTree(process);
            throw new IllegalStateException("Recovery command timed out: " + command);
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), () -> String.join(" ", command)
                + " failed:\n" + output);
    }

    private static void terminateProcessTree(Process process)
            throws InterruptedException {
        List<ProcessHandle> handles = new ArrayList<>();
        try {
            handles.addAll(process.toHandle().descendants().toList());
        } catch (RuntimeException ignored) {
            // The scripts forward TERM to their current database child when
            // process enumeration is restricted by the runtime.
        }
        Collections.reverse(handles);
        handles.add(process.toHandle());
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroy();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (handles.stream().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        process.waitFor(10, TimeUnit.SECONDS);
    }

    private static String hash(String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return List.class.isAssignableFrom(type) ? List.of() : null;
        }
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        return 0;
    }

    private static final class JdbcRepositories {
        private final HikariDataSource database;

        private JdbcRepositories(HikariDataSource database) {
            this.database = database;
        }

        private ConfigResourceRepository configResources() {
            return (ConfigResourceRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ConfigResourceRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findAll" -> loadConfigResources();
                        case "find" -> loadConfigResources().stream()
                                .filter(value -> value.getNamespaceId().equals(
                                                ((cloud.xuantong.resource.model.ConfigResourceKey)
                                                        args[0]).namespaceId())
                                        && value.getGroupName().equals(
                                                ((cloud.xuantong.resource.model.ConfigResourceKey)
                                                        args[0]).groupName())
                                        && value.getDataId().equals(
                                                ((cloud.xuantong.resource.model.ConfigResourceKey)
                                                        args[0]).dataId()))
                                .findFirst().orElse(null);
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ConfigReleaseRepository configReleases() {
            return (ConfigReleaseRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ConfigReleaseRepository.class},
                    (proxy, method, args) -> {
                        List<ConfigRelease> releases = loadConfigReleases();
                        return switch (method.getName()) {
                            case "findByDecisionRevision" -> releases.stream()
                                    .filter(value -> value.getConfigId().equals(args[0])
                                            && value.getDecisionRevision()
                                            == ((Number) args[1]).longValue())
                                    .findFirst().orElse(null);
                            case "findByContentRevision" -> releases.stream()
                                    .filter(value -> value.getConfigId().equals(args[0])
                                            && value.getContentRevision()
                                            == ((Number) args[1]).longValue())
                                    .toList();
                            case "findByReleaseId" -> releases.stream()
                                    .filter(value -> value.getReleaseId().equals(args[0]))
                                    .findFirst().orElse(null);
                            case "findByConfigId" -> releases.stream()
                                    .filter(value -> value.getConfigId().equals(args[0]))
                                    .toList();
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private ConfigRolloutRepository configRollouts() {
            return (ConfigRolloutRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ConfigRolloutRepository.class},
                    (proxy, method, args) -> defaultValue(method.getReturnType()));
        }

        private ServiceDefinitionRepository serviceDefinitions() {
            return (ServiceDefinitionRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ServiceDefinitionRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findAll" -> loadServiceDefinitions();
                        case "find" -> loadServiceDefinitions().stream()
                                .filter(value -> value.getNamespaceId().equals(
                                                ((cloud.xuantong.resource.model.ServiceKey)
                                                        args[0]).namespaceId())
                                        && value.getGroupName().equals(
                                                ((cloud.xuantong.resource.model.ServiceKey)
                                                        args[0]).groupName())
                                        && value.getServiceName().equals(
                                                ((cloud.xuantong.resource.model.ServiceKey)
                                                        args[0]).serviceName()))
                                .findFirst().orElse(null);
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private List<ConfigResource> loadConfigResources() throws SQLException {
            try (Connection connection = database.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT id, namespace_id, group_name, data_id, content,
                                content_type, checksum, revision, draft_revision,
                                lifecycle_status, description, created_by,
                                updated_by, created_at, updated_at
                         FROM config_resource
                         ORDER BY namespace_id, group_name, data_id
                         """)) {
                List<ConfigResource> values = new ArrayList<>();
                while (result.next()) {
                    ConfigResource value = new ConfigResource();
                    value.setId(result.getLong("id"));
                    value.setNamespaceId(result.getString("namespace_id"));
                    value.setGroupName(result.getString("group_name"));
                    value.setDataId(result.getString("data_id"));
                    value.setContent(result.getString("content"));
                    value.setContentType(result.getString("content_type"));
                    value.setChecksum(result.getString("checksum"));
                    value.setRevision(result.getLong("revision"));
                    value.setDraftRevision(result.getLong("draft_revision"));
                    value.setLifecycleStatus(result.getString("lifecycle_status"));
                    value.setDescription(result.getString("description"));
                    value.setCreatedBy(result.getString("created_by"));
                    value.setUpdatedBy(result.getString("updated_by"));
                    value.setCreatedAt(date(result, "created_at"));
                    value.setUpdatedAt(date(result, "updated_at"));
                    values.add(value);
                }
                return values;
            }
        }

        private List<ConfigRelease> loadConfigReleases() throws SQLException {
            try (Connection connection = database.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT id, release_id, config_id, namespace_id, group_name,
                                data_id, revision, content_revision, decision_revision,
                                event_revision, content, content_type, checksum,
                                release_type, operator, operation_id, released_at
                         FROM config_release
                         ORDER BY revision
                         """)) {
                List<ConfigRelease> values = new ArrayList<>();
                while (result.next()) {
                    ConfigRelease value = new ConfigRelease();
                    value.setId(result.getLong("id"));
                    value.setReleaseId(result.getString("release_id"));
                    value.setConfigId(result.getLong("config_id"));
                    value.setNamespaceId(result.getString("namespace_id"));
                    value.setGroupName(result.getString("group_name"));
                    value.setDataId(result.getString("data_id"));
                    value.setRevision(result.getLong("revision"));
                    value.setContentRevision(result.getLong("content_revision"));
                    value.setDecisionRevision(result.getLong("decision_revision"));
                    value.setEventRevision(result.getLong("event_revision"));
                    value.setContent(result.getString("content"));
                    value.setContentType(result.getString("content_type"));
                    value.setChecksum(result.getString("checksum"));
                    value.setReleaseType(result.getString("release_type"));
                    value.setOperator(result.getString("operator"));
                    value.setOperationId(result.getString("operation_id"));
                    value.setReleasedAt(date(result, "released_at"));
                    values.add(value);
                }
                return values;
            }
        }

        private List<ServiceDefinition> loadServiceDefinitions() throws SQLException {
            try (Connection connection = database.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT id, namespace_id, group_name, service_name,
                                description, metadata, service_generation,
                                lifecycle_state, lifecycle_operation_id,
                                lifecycle_error, created_by, created_at, updated_at
                         FROM service_definition
                         ORDER BY namespace_id, group_name, service_name
                         """)) {
                List<ServiceDefinition> values = new ArrayList<>();
                while (result.next()) {
                    ServiceDefinition value = new ServiceDefinition();
                    value.setId(result.getLong("id"));
                    value.setNamespaceId(result.getString("namespace_id"));
                    value.setGroupName(result.getString("group_name"));
                    value.setServiceName(result.getString("service_name"));
                    value.setDescription(result.getString("description"));
                    value.setMetadata(result.getString("metadata"));
                    value.setServiceGeneration(result.getLong("service_generation"));
                    value.setLifecycleState(result.getString("lifecycle_state"));
                    value.setLifecycleOperationId(result.getString(
                            "lifecycle_operation_id"));
                    value.setLifecycleError(result.getString("lifecycle_error"));
                    value.setCreatedBy(result.getString("created_by"));
                    value.setCreatedAt(date(result, "created_at"));
                    value.setUpdatedAt(date(result, "updated_at"));
                    values.add(value);
                }
                return values;
            }
        }

        private static Date date(ResultSet result, String column) throws SQLException {
            java.sql.Timestamp value = result.getTimestamp(column);
            return value == null ? null : new Date(value.getTime());
        }
    }
}
