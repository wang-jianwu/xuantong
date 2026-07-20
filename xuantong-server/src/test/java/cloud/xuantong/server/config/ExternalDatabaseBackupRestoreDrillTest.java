package cloud.xuantong.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in real database dump/import tests for the supported external dialects.
 *
 * <p>Each test creates two uniquely named temporary databases on an explicitly
 * configured isolated server. The source is migrated and seeded, the production
 * backup/import scripts restore it into the empty target, and a second import is
 * required to fail closed. Both databases are dropped in {@code finally};
 * command timeout terminates the script and its database child, while cleanup
 * retries without replacing the original failure.</p>
 */
class ExternalDatabaseBackupRestoreDrillTest {
    private static final String DATABASE_PREFIX = "xuantong_restore_drill_";
    private static final String NAMESPACE = "public";
    private static final String GROUP = "DEFAULT_GROUP";

    @TempDir
    Path tempDirectory;

    @Test
    void restoresRealMySqlDumpAndRejectsNonEmptyTarget() throws Exception {
        DatabaseServer server = DatabaseServer.fromEnvironment("MYSQL", "mysql", 3306);
        Assumptions.assumeTrue(server.configured(),
                "Set XUANTONG_RECOVERY_MYSQL_HOST to run the MySQL recovery drill");
        drill(server);
    }

    @Test
    void restoresRealPostgreSqlDumpAndRejectsNonEmptyTarget() throws Exception {
        DatabaseServer server = DatabaseServer.fromEnvironment("PGSQL", "pgsql", 5432);
        Assumptions.assumeTrue(server.configured(),
                "Set XUANTONG_RECOVERY_PGSQL_HOST to run the PostgreSQL recovery drill");
        drill(server);
    }

    private void drill(DatabaseServer server) throws Exception {
        requireClientCommands(server);
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10);
        String sourceDatabase = DATABASE_PREFIX + server.dialect() + "_source_" + suffix;
        String targetDatabase = DATABASE_PREFIX + server.dialect() + "_target_" + suffix;
        requireSafeDatabaseName(sourceDatabase);
        requireSafeDatabaseName(targetDatabase);

        String dataId = "restore-" + server.dialect() + ".yml";
        String content = "dialect: " + server.dialect() + "\nvalue: restored\n";
        String checksum = sha256(content);
        Path backup = tempDirectory.resolve(
                server.dialect().equals("mysql") ? "mysql.sql" : "postgres.dump");

        boolean sourceCreated = false;
        boolean targetCreated = false;
        Throwable primaryFailure = null;
        try {
            createDatabase(server, sourceDatabase);
            sourceCreated = true;
            createDatabase(server, targetDatabase);
            targetCreated = true;

            try (HikariDataSource source = dataSource(server, sourceDatabase)) {
                SchemaMigrationManager.MigrationSummary migration =
                        SchemaMigrationManager.migrate(source, server.dialect());
                assertEquals(SchemaMigrationManager.CURRENT_VERSION,
                        migration.currentVersion());
                seed(source, dataId, content, checksum, server.dialect());
            }

            runSuccess(server, dumpCommand(
                    server, sourceDatabase, backup));
            assertTrue(Files.size(backup) > 0L);
            runSuccess(server, importCommand(
                    server, targetDatabase, backup));

            try (HikariDataSource source = dataSource(server, sourceDatabase);
                 HikariDataSource target = dataSource(server, targetDatabase)) {
                verifyRestored(target, dataId, content, checksum, server.dialect());
                assertEquals(snapshot(source, dataId), snapshot(target, dataId));
            }

            CommandResult refused = run(server, importCommand(
                    server, targetDatabase, backup));
            assertTrue(refused.exitCode() != 0,
                    () -> "Second import unexpectedly succeeded:\n" + refused.output());
            assertTrue(refused.output().contains("Refusing to import into non-empty"),
                    () -> "Unexpected non-empty refusal output:\n" + refused.output());

            try (HikariDataSource target = dataSource(server, targetDatabase)) {
                assertEquals(1L, countCanary(target, dataId));
            }
        } catch (Exception | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            RuntimeException cleanupFailure = null;
            if (targetCreated) {
                cleanupFailure = cleanupDatabase(
                        server, targetDatabase, cleanupFailure);
            }
            if (sourceCreated) {
                cleanupFailure = cleanupDatabase(
                        server, sourceDatabase, cleanupFailure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private RuntimeException cleanupDatabase(
            DatabaseServer server,
            String database,
            RuntimeException previousFailure) {
        try {
            dropDatabase(server, database);
            return previousFailure;
        } catch (RuntimeException e) {
            if (previousFailure == null) {
                return e;
            }
            previousFailure.addSuppressed(e);
            return previousFailure;
        }
    }

    private void seed(
            HikariDataSource dataSource,
            String dataId,
            String content,
            String checksum,
            String dialect) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long configId;
                try (PreparedStatement resource = connection.prepareStatement("""
                        INSERT INTO config_resource (
                            namespace_id, group_name, data_id, content, content_type,
                            checksum, revision, draft_revision, lifecycle_status,
                            created_by, updated_by)
                        VALUES (?, ?, ?, ?, 'yaml', ?, 1, 0, 'ACTIVE', 'drill', 'drill')
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    resource.setString(1, NAMESPACE);
                    resource.setString(2, GROUP);
                    resource.setString(3, dataId);
                    resource.setString(4, content);
                    resource.setString(5, checksum);
                    assertEquals(1, resource.executeUpdate());
                    try (ResultSet keys = resource.getGeneratedKeys()) {
                        assertTrue(keys.next());
                        configId = keys.getLong(1);
                    }
                }
                try (PreparedStatement release = connection.prepareStatement("""
                        INSERT INTO config_release (
                            release_id, config_id, namespace_id, group_name, data_id,
                            revision, content_revision, decision_revision, event_revision,
                            content, content_type, checksum, release_type, operator,
                            operation_id)
                        VALUES (?, ?, ?, ?, ?, 1, 1, 1, 1, ?, 'yaml', ?, 'FULL',
                                'drill', ?)
                        """)) {
                    release.setString(1, "release-" + dialect);
                    release.setLong(2, configId);
                    release.setString(3, NAMESPACE);
                    release.setString(4, GROUP);
                    release.setString(5, dataId);
                    release.setString(6, content);
                    release.setString(7, checksum);
                    release.setString(8, "restore-drill-" + dialect);
                    assertEquals(1, release.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void verifyRestored(
            HikariDataSource target,
            String dataId,
            String content,
            String checksum,
            String dialect) throws Exception {
        SchemaMigrationManager.MigrationSummary migration =
                SchemaMigrationManager.migrate(target, dialect);
        assertEquals(SchemaMigrationManager.CURRENT_VERSION,
                migration.currentVersion());
        assertEquals(0, migration.migrationsExecuted());
        assertEquals(3, migration.appliedMigrations());

        try (Connection connection = target.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.content, r.checksum, r.revision, r.lifecycle_status,
                            l.release_type, l.operation_id
                     FROM config_resource r
                     JOIN config_release l ON l.config_id = r.id
                     WHERE r.namespace_id = ? AND r.group_name = ? AND r.data_id = ?
                     """)) {
            statement.setString(1, NAMESPACE);
            statement.setString(2, GROUP);
            statement.setString(3, dataId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(content, result.getString("content"));
                assertEquals(checksum, result.getString("checksum"));
                assertEquals(1L, result.getLong("revision"));
                assertEquals("ACTIVE", result.getString("lifecycle_status"));
                assertEquals("FULL", result.getString("release_type"));
                assertEquals("restore-drill-" + dialect,
                        result.getString("operation_id"));
                assertTrue(!result.next());
            }
        }
    }

    private DatabaseSnapshot snapshot(
            HikariDataSource dataSource, String dataId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return new DatabaseSnapshot(
                    count(connection, "config_namespace"),
                    count(connection, "resource_group"),
                    count(connection, "config_resource"),
                    count(connection, "config_release"),
                    count(connection, SchemaMigrationManager.HISTORY_TABLE),
                    countCanary(connection, dataId));
        }
    }

    private long countCanary(HikariDataSource dataSource, String dataId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return countCanary(connection, dataId);
        }
    }

    private long countCanary(Connection connection, String dataId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM config_resource
                WHERE namespace_id = ? AND group_name = ? AND data_id = ?
                """)) {
            statement.setString(1, NAMESPACE);
            statement.setString(2, GROUP);
            statement.setString(3, dataId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        String quoted = quote(connection, table);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + quoted)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String value = connection.getMetaData().getIdentifierQuoteString();
        if (value == null || value.isBlank()) {
            return identifier;
        }
        String quote = value.trim();
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private void createDatabase(DatabaseServer server, String database) throws SQLException {
        requireSafeDatabaseName(database);
        try (Connection connection = adminConnection(server);
             Statement statement = connection.createStatement()) {
            if (server.dialect().equals("mysql")) {
                statement.executeUpdate("CREATE DATABASE `" + database
                        + "` CHARACTER SET utf8mb4");
            } else {
                statement.executeUpdate("CREATE DATABASE \"" + database + "\"");
            }
        }
    }

    private void dropDatabase(DatabaseServer server, String database) {
        requireSafeDatabaseName(database);
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection connection = adminConnection(server)) {
                if (server.dialect().equals("pgsql")) {
                    try (PreparedStatement terminate = connection.prepareStatement("""
                            SELECT pg_terminate_backend(pid)
                            FROM pg_stat_activity
                            WHERE datname = ? AND pid <> pg_backend_pid()
                            """)) {
                        terminate.setString(1, database);
                        terminate.execute();
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    if (server.dialect().equals("mysql")) {
                        statement.executeUpdate(
                                "DROP DATABASE IF EXISTS `" + database + "`");
                    } else {
                        statement.executeUpdate(
                                "DROP DATABASE IF EXISTS \"" + database + "\"");
                    }
                }
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

    private Connection adminConnection(DatabaseServer server) throws SQLException {
        return java.sql.DriverManager.getConnection(
                server.jdbcUrl(server.adminDatabase()),
                server.user(),
                server.password());
    }

    private HikariDataSource dataSource(DatabaseServer server, String database) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(server.jdbcUrl(database));
        config.setUsername(server.user());
        config.setPassword(server.password());
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        return new HikariDataSource(config);
    }

    private List<String> dumpCommand(
            DatabaseServer server, String sourceDatabase, Path backup) {
        return List.of(
                repositoryRoot().resolve("scripts/dump-xuantong-database.sh").toString(),
                "--dialect", server.dialect(),
                "--host", server.host(),
                "--port", Integer.toString(server.port()),
                "--database", sourceDatabase,
                "--user", server.user(),
                "--output", backup.toString());
    }

    private List<String> importCommand(
            DatabaseServer server, String targetDatabase, Path backup) {
        return List.of(
                repositoryRoot().resolve("scripts/import-xuantong-database.sh").toString(),
                "--dialect", server.dialect(),
                "--input", backup.toString(),
                "--host", server.host(),
                "--port", Integer.toString(server.port()),
                "--database", targetDatabase,
                "--user", server.user(),
                "--target-empty-confirmed",
                "--confirm-restore");
    }

    private void runSuccess(DatabaseServer server, List<String> command)
            throws Exception {
        CommandResult result = run(server, command);
        assertEquals(0, result.exitCode(), () -> String.join(" ", command)
                + " failed:\n" + result.output());
    }

    private CommandResult run(DatabaseServer server, List<String> command)
            throws Exception {
        Path outputFile = Files.createTempFile(
                tempDirectory, "database-recovery-command-", ".log");
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
        builder.environment().put("XUANTONG_DB_PASSWORD", server.password());
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            terminateProcessTree(process);
            throw new IllegalStateException("Database recovery command timed out: "
                    + command);
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    static void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> handles = new ArrayList<>();
        try {
            handles.addAll(process.toHandle().descendants().toList());
        } catch (RuntimeException ignored) {
            // Restricted runtimes may deny process enumeration. The database
            // scripts forward TERM to their current child as a fallback.
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

    private void requireClientCommands(DatabaseServer server) throws Exception {
        for (String command : server.dialect().equals("mysql")
                ? List.of("mysql", "mysqldump")
                : List.of("psql", "pg_dump", "pg_restore")) {
            Process process = new ProcessBuilder("sh", "-c", "command -v " + command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(),
                    () -> command + " is required for the recovery drill: " + output);
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

    private static void requireSafeDatabaseName(String database) {
        if (database == null
                || !database.startsWith(DATABASE_PREFIX)
                || !database.matches("[a-z0-9_]{20,63}")) {
            throw new IllegalArgumentException(
                    "Unsafe recovery drill database name: " + database);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record DatabaseServer(
            String dialect,
            String host,
            int port,
            String user,
            String password) {

        private static DatabaseServer fromEnvironment(
                String prefix, String dialect, int defaultPort) {
            String host = environment("XUANTONG_RECOVERY_" + prefix + "_HOST", "");
            String portValue = environment(
                    "XUANTONG_RECOVERY_" + prefix + "_PORT",
                    Integer.toString(defaultPort));
            String user = environment(
                    "XUANTONG_RECOVERY_" + prefix + "_USER",
                    dialect.equals("mysql") ? "root" : "postgres");
            String password = environment(
                    "XUANTONG_RECOVERY_" + prefix + "_PASSWORD", "");
            int port;
            try {
                port = Integer.parseInt(portValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid recovery database port: " + portValue, e);
            }
            return new DatabaseServer(dialect, host, port, user, password);
        }

        private boolean configured() {
            return host != null && !host.isBlank();
        }

        private String adminDatabase() {
            return dialect.equals("mysql") ? "mysql" : "postgres";
        }

        private String jdbcUrl(String database) {
            if (dialect.equals("mysql")) {
                return "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&characterEncoding=utf8&connectTimeout=10000"
                        + "&socketTimeout=30000&tcpKeepAlive=true";
            }
            return "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=10&socketTimeout=30&tcpKeepAlive=true";
        }
    }

    private record DatabaseSnapshot(
            long namespaces,
            long groups,
            long resources,
            long releases,
            long migrations,
            long canaries) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
