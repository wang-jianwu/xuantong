package cloud.xuantong.server.config;

import javax.sql.DataSource;

/** Test-only access to the package-private production migration boundary. */
public final class TestSchemaMigration {
    private TestSchemaMigration() {
    }

    public static void migrateH2(DataSource dataSource) throws Exception {
        SchemaMigrationManager.migrate(dataSource, "h2");
    }

    public static void migrateMySql(DataSource dataSource) throws Exception {
        SchemaMigrationManager.migrate(dataSource, "mysql");
    }
}
