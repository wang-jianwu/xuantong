package cloud.xuantong.server.config;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;

/**
 * Database bootstrap for the pure 2.0 line.
 * Versioned migrations are executed by Flyway before repositories start.
 */
@Component
public class DbInitializer {
    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    @Inject
    private DataSource dataSource;
    @Inject("${security.production:false}")
    private boolean production;
    @Inject("${easy-query.xuantong.database:h2}")
    private String databaseDialect;

    @Init(index = -1000)
    public void init() {
        try {
            String dialect = normalizeDialect(databaseDialect);
            SchemaMigrationManager.MigrationSummary summary =
                    SchemaMigrationManager.migrate(dataSource, dialect);
            try (Connection connection = dataSource.getConnection()) {
                verifySchemaCompatibility(connection, dialect);
            }
            verifyProductionAdminPassword(dialect);
            log.info("Database schema ready: dialect={}, version={}, migrationsExecuted={}, "
                            + "appliedMigrations={}",
                    summary.dialect(),
                    summary.currentVersion(),
                    summary.migrationsExecuted(),
                    summary.appliedMigrations());
        } catch (Exception e) {
            log.error("Database initialization failed", e);
            throw new IllegalStateException(
                    "Database initialization failed: " + e.getMessage(), e);
        }
    }

    private void verifyProductionAdminPassword(String dialect) throws Exception {
        if (!production) return;
        String userTable = "pgsql".equals(dialect) ? "\"user\"" : "`user`";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(
                     "SELECT password FROM " + userTable + " WHERE username = 'admin'")) {
            if (!result.next()) return;
            String passwordHash = result.getString(1);
            boolean defaultPassword = SecureUtil.md5("admin123").equals(passwordHash)
                    || (passwordHash != null && passwordHash.startsWith("$2")
                    && BCrypt.checkpw("admin123", passwordHash));
            if (defaultPassword) {
                throw new IllegalStateException(
                        "Production mode forbids the default admin password. Change admin123 before startup.");
            }
        }
    }

    static String normalizeDialect(String dialect) {
        String normalized = dialect == null ? "h2" : dialect.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "h2" -> "h2";
            case "mysql" -> "mysql";
            case "postgres", "postgresql", "pgsql" -> "pgsql";
            default -> throw new IllegalArgumentException("Unsupported database dialect: " + dialect
                    + ". Supported dialects: h2, mysql, pgsql");
        };
    }

    static void verifySchemaCompatibility(Connection connection, String dialect) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        boolean userTableExists = false;
        boolean adminLoginGuardExists = false;
        boolean legacyResourceTableExists = false;
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (tableName == null) continue;
                String normalized = tableName.toLowerCase(Locale.ROOT);
                if ("user".equals(normalized)) {
                    userTableExists = true;
                }
                if ("admin_login_guard".equals(normalized)) {
                    adminLoginGuardExists = true;
                }
                if (Set.of("project", "environment", "config_item", "config_log")
                        .contains(normalized)) {
                    legacyResourceTableExists = true;
                }
            }
        }
        if (legacyResourceTableExists) {
            throw incompatibleSchema();
        }
        if (!userTableExists) {
            return;
        }

        boolean configReleaseExists = false;
        boolean configResourceExists = false;
        boolean configResourceDraftRevision = false;
        boolean configResourceLifecycleStatus = false;
        boolean configReleaseContentRevision = false;
        boolean configReleaseOperationId = false;
        boolean configRolloutExists = false;
        boolean configRolloutStartOperationId = false;
        boolean configRolloutKey = false;
        boolean auditLogExists = false;
        boolean auditLogOperationId = false;
        boolean clientAccessTokenExists = false;
        boolean clientAccessTokenTenant = false;
        boolean userSecurityVersion = false;
        try (ResultSet columns = metadata.getColumns(
                connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (columns.next()) {
                String tableName = columns.getString("TABLE_NAME");
                String columnName = columns.getString("COLUMN_NAME");
                if ("user".equalsIgnoreCase(tableName)
                        && "role".equalsIgnoreCase(columnName)
                        && columns.getInt("COLUMN_SIZE") < 32) {
                    throw incompatibleSchema();
                }
                if ("user".equalsIgnoreCase(tableName)
                        && "security_version".equalsIgnoreCase(columnName)) {
                    userSecurityVersion = true;
                }
                if ("config_release".equalsIgnoreCase(tableName)) {
                    configReleaseExists = true;
                    if ("content_revision".equalsIgnoreCase(columnName)) {
                        configReleaseContentRevision = true;
                    }
                    if ("operation_id".equalsIgnoreCase(columnName)) {
                        configReleaseOperationId = true;
                    }
                }
                if ("config_resource".equalsIgnoreCase(tableName)) {
                    configResourceExists = true;
                    if ("draft_revision".equalsIgnoreCase(columnName)) {
                        configResourceDraftRevision = true;
                    }
                    if ("lifecycle_status".equalsIgnoreCase(columnName)) {
                        configResourceLifecycleStatus = true;
                    }
                }
                if ("config_rollout".equalsIgnoreCase(tableName)) {
                    configRolloutExists = true;
                    if ("start_operation_id".equalsIgnoreCase(columnName)) {
                        configRolloutStartOperationId = true;
                    }
                    if ("rollout_key".equalsIgnoreCase(columnName)) {
                        configRolloutKey = true;
                    }
                }
                if ("audit_log".equalsIgnoreCase(tableName)) {
                    auditLogExists = true;
                    if ("operation_id".equalsIgnoreCase(columnName)) {
                        auditLogOperationId = true;
                    }
                }
                if ("client_access_token".equalsIgnoreCase(tableName)) {
                    clientAccessTokenExists = true;
                    if ("tenant".equalsIgnoreCase(columnName)) {
                        clientAccessTokenTenant = true;
                    }
                }
            }
        }
        if (!adminLoginGuardExists
                || !userSecurityVersion
                || (configResourceExists
                    && (!configResourceDraftRevision || !configResourceLifecycleStatus))
                || (configReleaseExists
                    && (!configReleaseContentRevision || !configReleaseOperationId))
                || (configRolloutExists
                    && (!configRolloutStartOperationId || !configRolloutKey))
                || (auditLogExists && !auditLogOperationId)
                || (clientAccessTokenExists && !clientAccessTokenTenant)) {
            throw incompatibleSchema();
        }

        String userTable = "pgsql".equals(dialect) ? "\"user\"" : "`user`";
        try (Statement statement = connection.createStatement();
             ResultSet roles = statement.executeQuery("SELECT role FROM " + userTable)) {
            while (roles.next()) {
                String role = roles.getString(1);
                if (role != null && !Set.of(
                        "SYSTEM_ADMIN", "NAMESPACE_ADMIN", "DEVELOPER", "VIEWER")
                        .contains(role)) {
                    throw incompatibleSchema();
                }
            }
        }
    }

    private static IllegalStateException incompatibleSchema() {
        return new IllegalStateException(
                "Detected an incompatible Xuantong legacy or pre-release database. "
                        + "Xuantong 2.0 does not migrate legacy schemas. Delete the local "
                        + "development database or configure a new empty database with "
                        + "XUANTONG_DB_URL. The default path is ./data/xuantong-2, "
                        + "and production data must be exported explicitly before replacement.");
    }
}
