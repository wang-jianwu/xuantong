package cloud.xuantong.server.cluster;

import cloud.xuantong.security.repository.impl.ClientAccessTokenRepositoryImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAccessTokenRevocationRepositoryTest {
    @Test
    void revokesTokenAndAppendsDurableEventInOneTransaction() throws Exception {
        try (HikariDataSource dataSource = dataSource("token_revocation")) {
            migrate(dataSource);
            insertToken(dataSource, "hash-a");
            ClientAccessTokenRepositoryImpl repository =
                    new ClientAccessTokenRepositoryImpl(dataSource);

            assertEquals(1L, repository.revoke(1L));
            assertEquals(0L, count(dataSource,
                    "SELECT COUNT(*) FROM client_access_token WHERE is_active = TRUE"));
            assertEquals(1L, count(dataSource,
                    "SELECT COUNT(*) FROM credential_revocation_event "
                            + "WHERE token_hash = 'hash-a'"));
        }
    }

    @Test
    void rollsBackTokenStateWhenRevocationEventCannotBePersisted() throws Exception {
        try (HikariDataSource dataSource = dataSource("token_revocation_rollback")) {
            migrate(dataSource);
            insertToken(dataSource, "hash-b");
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE credential_revocation_event");
            }
            ClientAccessTokenRepositoryImpl repository =
                    new ClientAccessTokenRepositoryImpl(dataSource);

            assertThrows(IllegalStateException.class, () -> repository.revoke(1L));
            assertEquals(1L, count(dataSource,
                    "SELECT COUNT(*) FROM client_access_token WHERE is_active = TRUE"));
        }
    }

    private void insertToken(HikariDataSource dataSource, String hash) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO client_access_token "
                    + "(token_name, token_hash, tenant, namespace_id, group_name, is_active) "
                    + "VALUES ('test', '" + hash + "', 'default', 'public', "
                    + "'DEFAULT_GROUP', TRUE)");
        }
    }

    private long count(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void migrate(HikariDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .table("test_schema_history")
                .load()
                .migrate();
    }

    private HikariDataSource dataSource(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }
}
