package cloud.xuantong.server.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSchemaIntegrationTest {
    private static final Set<String> BUSINESS_TABLES = Set.of(
            "user",
            "config_namespace",
            "resource_group",
            "config_resource",
            "config_release",
            "config_rollout",
            "config_state_operation",
            "service_definition",
            "audit_log",
            "client_access_token",
            "credential_revocation_event",
            "gateway_runtime_snapshot",
            "user_scope_role",
            "admin_login_guard"
    );

    @Test
    void initializesH2SchemaIdempotently() throws Exception {
        verifySchema("h2", "jdbc:h2:mem:schema_h2", "sa", "", "`user`");
    }

    @Test
    void initializesMySqlSchemaInCompatibilityMode() throws Exception {
        verifySchema("mysql",
                "jdbc:h2:mem:schema_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "sa", "", "`user`");
    }

    @Test
    void initializesPostgreSqlSchemaInCompatibilityMode() throws Exception {
        verifySchema("pgsql",
                "jdbc:h2:mem:schema_pgsql;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "sa", "", "\"user\"");
    }

    @Test
    void upgradesAuditedTwoPointZeroZeroSchemaToCurrent(@TempDir Path tempDir)
            throws Exception {
        Path initialMigration = tempDir.resolve("V2_0_0__initial_schema.sql");
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/h2/V2_0_0__initial_schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("Initial H2 migration resource is missing");
            }
            Files.copy(input, initialMigration);
        }

        try (HikariDataSource dataSource = dataSource(
                "jdbc:h2:mem:upgrade_2_0_0_to_current", "sa", "")) {
            SchemaMigrationManager.MigrationSummary initial =
                    SchemaMigrationManager.migrate(
                            dataSource, "h2", "filesystem:" + tempDir);
            SchemaMigrationManager.MigrationSummary upgraded =
                    SchemaMigrationManager.migrate(dataSource, "h2");

            assertEquals("2.0.0", initial.currentVersion());
            assertEquals(1, initial.migrationsExecuted());
            assertEquals(SchemaMigrationManager.CURRENT_VERSION,
                    upgraded.currentVersion());
            assertEquals(2, upgraded.migrationsExecuted());
            assertEquals(3, upgraded.appliedMigrations());
            try (Connection connection = dataSource.getConnection()) {
                assertTrue(hasIndex(connection, "config_resource",
                        "idx_config_resource_list"));
                assertTrue(hasIndex(connection, "user", "idx_user_role_list"));
                assertTrue(hasIndex(connection, "gateway_runtime_snapshot",
                        "idx_gateway_runtime_lease"));
            }
        }
    }

    @Test
    void upgradesAuditedTwoPointZeroOneSchemaToCurrent(@TempDir Path tempDir)
            throws Exception {
        copyMigration(tempDir, "V2_0_0__initial_schema.sql");
        copyMigration(tempDir, "V2_0_1__management_query_indexes.sql");

        try (HikariDataSource dataSource = dataSource(
                "jdbc:h2:mem:upgrade_2_0_1_to_current", "sa", "")) {
            SchemaMigrationManager.MigrationSummary initial =
                    SchemaMigrationManager.migrate(
                            dataSource, "h2", "filesystem:" + tempDir);
            SchemaMigrationManager.MigrationSummary upgraded =
                    SchemaMigrationManager.migrate(dataSource, "h2");

            assertEquals("2.0.1", initial.currentVersion());
            assertEquals(2, initial.migrationsExecuted());
            assertEquals(SchemaMigrationManager.CURRENT_VERSION,
                    upgraded.currentVersion());
            assertEquals(1, upgraded.migrationsExecuted());
            assertEquals(3, upgraded.appliedMigrations());
            try (Connection connection = dataSource.getConnection()) {
                assertTrue(tableExists(connection, "gateway_runtime_snapshot"));
                assertTrue(tableExists(connection, "credential_revocation_event"));
            }
        }
    }

    @Test
    void initializesRealMySqlWhenConfigured() throws Exception {
        String url = System.getenv("XUANTONG_TEST_MYSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set XUANTONG_TEST_MYSQL_URL to run the real MySQL schema test");
        verifySchema("mysql", url,
                env("XUANTONG_TEST_MYSQL_USER", "root"),
                env("XUANTONG_TEST_MYSQL_PASSWORD", ""), "`user`");
    }

    @Test
    void connectsToRealMySqlReadOnlyWhenConfigured() throws Exception {
        String url = System.getenv("XUANTONG_TEST_MYSQL_SMOKE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set XUANTONG_TEST_MYSQL_SMOKE_URL to run the read-only MySQL connection test");
        try (Connection connection = DriverManager.getConnection(url,
                env("XUANTONG_TEST_MYSQL_USER", "root"),
                env("XUANTONG_TEST_MYSQL_PASSWORD", ""))) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertTrue(connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT).contains("mysql"));
        }
    }

    @Test
    void initializesRealPostgreSqlWhenConfigured() throws Exception {
        String url = System.getenv("XUANTONG_TEST_PGSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set XUANTONG_TEST_PGSQL_URL to run the real PostgreSQL schema test");
        verifySchema("pgsql", url,
                env("XUANTONG_TEST_PGSQL_USER", "postgres"),
                env("XUANTONG_TEST_PGSQL_PASSWORD", ""), "\"user\"");
    }

    @Test
    void normalizesSupportedDialectAliasesAndRejectsUnknownDialect() {
        assertEquals("h2", DbInitializer.normalizeDialect(null));
        assertEquals("mysql", DbInitializer.normalizeDialect(" MySQL "));
        assertEquals("pgsql", DbInitializer.normalizeDialect("postgres"));
        assertEquals("pgsql", DbInitializer.normalizeDialect("postgresql"));
        assertEquals("pgsql", DbInitializer.normalizeDialect("pgsql"));
        assertThrows(IllegalArgumentException.class,
                () -> DbInitializer.normalizeDialect("oracle"));
    }

    private void verifySchema(
            String dialect,
            String jdbcUrl,
            String username,
            String password,
            String userTable) throws Exception {
        try (HikariDataSource dataSource = dataSource(jdbcUrl, username, password)) {
            SchemaMigrationManager.MigrationSummary first =
                    SchemaMigrationManager.migrate(dataSource, dialect);
            SchemaMigrationManager.MigrationSummary second =
                    SchemaMigrationManager.migrate(dataSource, dialect);

            assertEquals(SchemaMigrationManager.CURRENT_VERSION, first.currentVersion());
            assertEquals(3, first.migrationsExecuted());
            assertEquals(0, second.migrationsExecuted());
            assertEquals(first.appliedMigrations(), second.appliedMigrations());

            try (Connection connection = dataSource.getConnection()) {
                DbInitializer.verifySchemaCompatibility(connection, dialect);

                assertBusinessTables(connection);
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM " + userTable
                                + " WHERE username = 'admin' AND role = 'SYSTEM_ADMIN'"
                                + " AND security_version = 1 AND password LIKE '$2%'"));
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM config_namespace WHERE namespace_id = 'public'"));
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM resource_group"
                                + " WHERE namespace_id = 'public' AND group_name = 'DEFAULT_GROUP'"));
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM " + quotedTable(
                                connection, SchemaMigrationManager.HISTORY_TABLE)
                                + " WHERE " + quotedIdentifier(connection, "version")
                                + " = '2.0.0' AND "
                                + quotedIdentifier(connection, "success") + " = TRUE"));
                assertTrue(hasColumn(connection, "config_resource", "lifecycle_status"));
                assertTrue(hasIndex(connection, "config_resource", "idx_config_resource_list"));
                assertTrue(hasIndex(connection, "audit_log", "idx_audit_resource_history"));
                assertTrue(hasIndex(connection, "user", "idx_user_list"));
                assertTrue(hasIndex(connection, "user", "idx_user_role_list"));
                assertTrue(hasIndex(connection, "gateway_runtime_snapshot",
                        "idx_gateway_runtime_lease"));
                assertTrue(hasIndex(connection, "credential_revocation_event",
                        "idx_credential_revocation_time"));
            }
        }
    }

    private void copyMigration(Path directory, String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/h2/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource is missing: " + name);
            }
            Files.copy(input, directory.resolve(name));
        }
    }

    @Test
    void rejectsLegacySchemaInsteadOfPartiallyMigratingIt() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:legacy_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `user` ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(50), role VARCHAR(10))");
            statement.execute("INSERT INTO `user` (username, role) VALUES ('admin', 'admin')");

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> SchemaMigrationManager.verifyMigrationBoundary(connection));
            assertTrue(error.getMessage().contains("does not baseline or migrate"));
        }
    }

    @Test
    void rejectsPreReleaseTwoPointZeroProjectionSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:pre_release_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `user` ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(50), role VARCHAR(32))");
            statement.execute("INSERT INTO `user` (username, role) "
                    + "VALUES ('admin', 'SYSTEM_ADMIN')");
            statement.execute("CREATE TABLE config_release ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, release_id VARCHAR(64))");

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> SchemaMigrationManager.verifyMigrationBoundary(connection));
            assertTrue(error.getMessage().contains("pre-release schema"));
        }
    }

    @Test
    void rejectsCompleteUnversionedTwoPointZeroSchema() throws Exception {
        try (HikariDataSource dataSource = dataSource(
                "jdbc:h2:mem:complete_unversioned_schema", "sa", "")) {
            SchemaMigrationManager.migrate(dataSource, "h2");
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + quotedTable(
                        connection, SchemaMigrationManager.HISTORY_TABLE));

                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> SchemaMigrationManager.verifyMigrationBoundary(connection));
                assertTrue(error.getMessage().contains("unversioned"));
            }
        }
    }

    @Test
    void rejectsModifiedMigrationChecksum() throws Exception {
        try (HikariDataSource dataSource = dataSource(
                "jdbc:h2:mem:modified_migration_checksum", "sa", "")) {
            SchemaMigrationManager.migrate(dataSource, "h2");
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + quotedTable(
                                connection, SchemaMigrationManager.HISTORY_TABLE)
                        + " SET " + quotedIdentifier(connection, "checksum")
                        + " = " + quotedIdentifier(connection, "checksum") + " + 1");
            }

            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> SchemaMigrationManager.migrate(dataSource, "h2"));
            assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("checksum"));
        }
    }

    @Test
    void rejectsAppliedMigrationHistoryOutsidePureTwoPointZeroLine() throws Exception {
        assertRejectedHistoryVersion("legacy_history_version", "1.3.3");
        assertRejectedHistoryVersion("future_history_version", "3.0.0");
    }

    @Test
    void propagatesMigrationFailureAndDoesNotReportSchemaReady(@TempDir Path tempDir)
            throws Exception {
        Path migration = tempDir.resolve("V2_0_1__broken_migration.sql");
        Files.writeString(migration, """
                CREATE TABLE migration_started (id BIGINT PRIMARY KEY);
                THIS IS NOT VALID SQL;
                CREATE TABLE migration_completed (id BIGINT PRIMARY KEY);
                """);
        try (HikariDataSource dataSource = dataSource(
                "jdbc:h2:mem:broken_migration", "sa", "")) {
            assertThrows(RuntimeException.class,
                    () -> SchemaMigrationManager.migrate(
                            dataSource, "h2", "filesystem:" + tempDir));
            try (Connection connection = dataSource.getConnection()) {
                assertTrue(tableExists(connection, "migration_started"));
                assertFalse(tableExists(connection, "migration_completed"));
                assertEquals(0, count(connection,
                        "SELECT COUNT(*) FROM " + quotedTable(
                                connection, SchemaMigrationManager.HISTORY_TABLE)
                                + " WHERE " + quotedIdentifier(connection, "success")
                                + " = TRUE AND " + quotedIdentifier(connection, "version")
                                + " = '2.0.1'"));
            }
        }
    }

    @Test
    void rejectsMigrationVersionsOutsidePureTwoPointZeroLine() {
        SchemaMigrationManager.requireSupportedVersion("2.0");
        SchemaMigrationManager.requireSupportedVersion("2.0.0");
        SchemaMigrationManager.requireSupportedVersion("2.0.7");
        assertThrows(IllegalStateException.class,
                () -> SchemaMigrationManager.requireSupportedVersion("1.3.3"));
        assertThrows(IllegalStateException.class,
                () -> SchemaMigrationManager.requireSupportedVersion("2"));
        assertThrows(IllegalStateException.class,
                () -> SchemaMigrationManager.requireSupportedVersion("2.1.0"));
        assertThrows(IllegalStateException.class,
                () -> SchemaMigrationManager.requireSupportedVersion("3.0.0"));
    }

    private void assertRejectedHistoryVersion(String databaseName, String version)
            throws Exception {
        try (HikariDataSource dataSource = dataSource(
                "jdbc:h2:mem:" + databaseName, "sa", "")) {
            SchemaMigrationManager.migrate(dataSource, "h2");
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + quotedTable(
                                connection, SchemaMigrationManager.HISTORY_TABLE)
                        + " SET " + quotedIdentifier(connection, "version")
                        + " = '" + version + "'");
            }

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> SchemaMigrationManager.migrate(dataSource, "h2"));
            assertTrue(error.getMessage().contains("Unsupported Xuantong database migration"));
        }
    }

    private String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    private void assertBusinessTables(Connection connection) throws Exception {
        Set<String> actual = new HashSet<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                actual.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(actual.containsAll(BUSINESS_TABLES),
                () -> "Missing tables: " + difference(BUSINESS_TABLES, actual));
    }

    private Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String quotedTable(Connection connection, String expectedName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String actualName = null;
        try (ResultSet tables = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String candidate = tables.getString("TABLE_NAME");
                if (expectedName.equalsIgnoreCase(candidate)) {
                    actualName = candidate;
                    break;
                }
            }
        }
        if (actualName == null) {
            throw new IllegalStateException("Table not found: " + expectedName);
        }
        return quoteIdentifier(metadata, actualName);
    }

    private String quotedIdentifier(Connection connection, String identifier) throws Exception {
        return quoteIdentifier(connection.getMetaData(), identifier);
    }

    private String quoteIdentifier(DatabaseMetaData metadata, String identifier) throws Exception {
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        quote = quote.trim();
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private boolean hasColumn(
            Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
                null, null, "%", "%")) {
            while (columns.next()) {
                if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(
                null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasIndex(
            Connection connection, String tableName, String indexName) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), tableName, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), tableName.toUpperCase(Locale.ROOT),
                false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private HikariDataSource dataSource(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setPoolName("schema-migration-test");
        return new HikariDataSource(config);
    }
}
