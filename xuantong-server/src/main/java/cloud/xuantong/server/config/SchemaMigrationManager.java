package cloud.xuantong.server.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Runs audited, dialect-specific database migrations for the pure 2.0 line. */
final class SchemaMigrationManager {
    static final String HISTORY_TABLE = "xuantong_schema_history";
    static final String INITIAL_VERSION = "2.0.0";
    static final String CURRENT_VERSION = "2.0.2";

    private static final Set<String> XUANTONG_TABLES = Set.of(
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
            "admin_login_guard");
    private static final Set<String> LEGACY_TABLES = Set.of(
            "project", "environment", "config_item", "config_log");

    private SchemaMigrationManager() {
    }

    static MigrationSummary migrate(DataSource dataSource, String dialect) throws Exception {
        String normalizedDialect = DbInitializer.normalizeDialect(dialect);
        return migrate(dataSource, normalizedDialect,
                "classpath:db/migration/" + normalizedDialect);
    }

    static MigrationSummary migrate(
            DataSource dataSource, String dialect, String migrationLocation) throws Exception {
        String normalizedDialect = DbInitializer.normalizeDialect(dialect);
        try (Connection connection = dataSource.getConnection()) {
            verifyMigrationBoundary(connection);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocation)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load();

        requireSupportedVersions(flyway.info().applied());
        MigrateResult result = flyway.migrate();
        MigrationInfo[] applied = flyway.info().applied();
        requireSupportedVersions(applied);
        MigrationInfo current = flyway.info().current();
        if (current == null || current.getVersion() == null) {
            throw new IllegalStateException(
                    "No versioned Xuantong 2.0 database migration was applied");
        }
        String currentVersion = current.getVersion().getVersion();
        requireSupportedVersion(currentVersion);
        return new MigrationSummary(
                normalizedDialect,
                currentVersion,
                result.migrationsExecuted,
                applied.length);
    }

    static void verifyMigrationBoundary(Connection connection) throws Exception {
        Set<String> tables = tableNames(connection);
        if (!disjoint(tables, LEGACY_TABLES)) {
            throw incompatibleSchema(
                    "legacy 1.x resource tables were found");
        }
        boolean hasHistory = tables.contains(HISTORY_TABLE);
        boolean hasXuantongTables = !disjoint(tables, XUANTONG_TABLES);
        if (hasXuantongTables && !hasHistory) {
            throw incompatibleSchema(
                    "an unversioned legacy or pre-release schema was found");
        }
    }

    static void requireSupportedVersions(MigrationInfo[] migrations) {
        if (migrations == null) {
            return;
        }
        for (MigrationInfo migration : migrations) {
            if (migration != null && migration.getVersion() != null) {
                requireSupportedVersion(migration.getVersion().getVersion());
            }
        }
    }

    static void requireSupportedVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (!normalized.matches("2\\.0(?:\\.\\d+)*")) {
            throw new IllegalStateException(
                    "Unsupported Xuantong database migration version " + version
                            + ". This release only accepts audited 2.0.x migrations.");
        }
    }

    private static Set<String> tableNames(Connection connection) throws Exception {
        Set<String> tables = new HashSet<>();
        try (ResultSet result = connection.getMetaData().getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (result.next()) {
                String name = result.getString("TABLE_NAME");
                if (name != null) {
                    tables.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tables;
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        for (String value : right) {
            if (left.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static IllegalStateException incompatibleSchema(String reason) {
        return new IllegalStateException(
                "Detected an incompatible Xuantong database: " + reason + ". "
                        + "Xuantong 2.0 does not baseline or migrate 1.x/pre-release schemas. "
                        + "Use a new empty database, then import business data through an "
                        + "explicitly reviewed procedure.");
    }

    record MigrationSummary(
            String dialect,
            String currentVersion,
            int migrationsExecuted,
            int appliedMigrations) {
    }
}
