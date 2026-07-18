package cloud.xuantong.server.config;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据库自动初始化 — 启动时按数据库方言执行对应 Schema
 * <p>
 * H2 内嵌模式下无需手动建表；MySQL/PG 也可自动初始化。
 * 使用 CREATE TABLE IF NOT EXISTS + INSERT ... WHERE NOT EXISTS 保证幂等。
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
            String schemaResource = "db/schema-" + dialect + ".sql";
            List<String> statements = loadStatements(schemaResource);
            if (statements.isEmpty()) {
                throw new IllegalStateException(schemaResource + " not found or empty");
            }

            log.info("Initializing {} database ({} statements)...", dialect, statements.size());
            int ok = 0;

            try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

                verifySchemaCompatibility(conn, dialect);

                for (String sql : statements) {
                    stmt.execute(sql);
                    ok++;
                }

            }

            verifyProductionAdminPassword(dialect);
            log.info("Database initialized: {} / {} statements executed", ok, statements.size());
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

    /**
     * 逐行读取 SQL 文件，按分号拆分为独立语句
     */
    private List<String> loadStatements(String resourceName) throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        if (is == null) return new ArrayList<>();

        List<String> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                // 跳过空行和注释
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;

                buf.append(line).append("\n");
                // 遇到分号 → 一条语句结束
                if (trimmed.endsWith(";")) {
                    String stmt = buf.toString().trim();
                    // 去掉末尾分号（JDBC 不需要）
                    if (stmt.endsWith(";")) {
                        stmt = stmt.substring(0, stmt.length() - 1);
                    }
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    buf.setLength(0);
                }
            }
            // 末尾可能有没有分号的语句
            String remaining = buf.toString().trim();
            if (!remaining.isEmpty()) {
                statements.add(remaining);
            }
        }
        return statements;
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
        boolean legacyResourceTableExists = false;
        try (ResultSet tables = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (tableName == null) continue;
                String normalized = tableName.toLowerCase(Locale.ROOT);
                if ("user".equals(normalized)) {
                    userTableExists = true;
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
        boolean configReleaseContentRevision = false;
        boolean configReleaseOperationId = false;
        boolean configRolloutExists = false;
        boolean configRolloutStartOperationId = false;
        boolean auditLogExists = false;
        boolean auditLogOperationId = false;
        boolean clientAccessTokenExists = false;
        boolean clientAccessTokenTenant = false;
        try (ResultSet columns = metadata.getColumns(null, null, "%", "%")) {
            while (columns.next()) {
                String tableName = columns.getString("TABLE_NAME");
                String columnName = columns.getString("COLUMN_NAME");
                if ("user".equalsIgnoreCase(tableName)
                        && "role".equalsIgnoreCase(columnName)
                        && columns.getInt("COLUMN_SIZE") < 32) {
                    throw incompatibleSchema();
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
                if ("config_rollout".equalsIgnoreCase(tableName)) {
                    configRolloutExists = true;
                    if ("start_operation_id".equalsIgnoreCase(columnName)) {
                        configRolloutStartOperationId = true;
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
        if ((configReleaseExists
                && (!configReleaseContentRevision || !configReleaseOperationId))
                || (configRolloutExists && !configRolloutStartOperationId)
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
