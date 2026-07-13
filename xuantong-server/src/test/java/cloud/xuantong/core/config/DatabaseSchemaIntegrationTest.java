package cloud.xuantong.core.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSchemaIntegrationTest {
    private static final Set<String> BUSINESS_TABLES = Set.of(
            "user",
            "config_namespace",
            "resource_group",
            "config_resource",
            "config_release",
            "service_definition",
            "audit_log",
            "client_access_token",
            "user_scope_role"
    );

    @Test
    void initializesH2SchemaIdempotently() throws Exception {
        verifySchema("schema-h2.sql", "jdbc:h2:mem:schema_h2", "sa", "", "`user`");
    }

    @Test
    void initializesMySqlSchemaInCompatibilityMode() throws Exception {
        verifySchema("schema-mysql.sql",
                "jdbc:h2:mem:schema_mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "sa", "", "`user`");
    }

    @Test
    void initializesPostgreSqlSchemaInCompatibilityMode() throws Exception {
        verifySchema("schema-pgsql.sql",
                "jdbc:h2:mem:schema_pgsql;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "sa", "", "\"user\"");
    }

    @Test
    void initializesRealMySqlWhenConfigured() throws Exception {
        String url = System.getenv("XUANTONG_TEST_MYSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set XUANTONG_TEST_MYSQL_URL to run the real MySQL schema test");
        verifySchema("schema-mysql.sql", url,
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
        verifySchema("schema-pgsql.sql", url,
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
            String schemaName,
            String jdbcUrl,
            String username,
            String password,
            String userTable) throws Exception {
        List<String> statements = loadStatements("/db/" + schemaName);
        assertTrue(statements.size() >= 12, schemaName + " should contain the complete 2.0 schema");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            DbInitializer.verifySchemaCompatibility(
                    connection, schemaName.contains("pgsql") ? "pgsql"
                            : schemaName.contains("mysql") ? "mysql" : "h2");
            executeAll(connection, statements);
            DbInitializer.verifySchemaCompatibility(
                    connection, schemaName.contains("pgsql") ? "pgsql"
                            : schemaName.contains("mysql") ? "mysql" : "h2");
            executeAll(connection, statements);

            assertBusinessTables(connection);
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM " + userTable
                            + " WHERE username = 'admin' AND role = 'SYSTEM_ADMIN'"));
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM config_namespace WHERE namespace_id = 'public'"));
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM resource_group"
                            + " WHERE namespace_id = 'public' AND group_name = 'DEFAULT_GROUP'"));
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
                    () -> DbInitializer.verifySchemaCompatibility(connection, "h2"));
            assertTrue(error.getMessage().contains("does not migrate legacy schemas"));
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

    private void executeAll(Connection connection, List<String> statements) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private List<String> loadStatements(String resourceName) throws Exception {
        InputStream input = getClass().getResourceAsStream(resourceName);
        assertNotNull(input, resourceName + " must exist");

        List<String> statements = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                buffer.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    statements.add(withoutTrailingSemicolon(buffer.toString()));
                    buffer.setLength(0);
                }
            }
        }
        if (!buffer.toString().isBlank()) {
            statements.add(withoutTrailingSemicolon(buffer.toString()));
        }
        return statements;
    }

    private String withoutTrailingSemicolon(String sql) {
        String result = sql.trim();
        return result.endsWith(";") ? result.substring(0, result.length() - 1) : result;
    }
}
