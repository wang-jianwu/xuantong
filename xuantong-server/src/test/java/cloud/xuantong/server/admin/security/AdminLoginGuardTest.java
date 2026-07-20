package cloud.xuantong.server.admin.security;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminLoginGuardTest {
    private JdbcDataSource dataSource;
    private MutableClock clock;
    private AdminSecurityProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admin_login_guard_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE admin_login_guard ("
                    + "guard_key VARCHAR(96) PRIMARY KEY, failure_count INT NOT NULL, "
                    + "window_started_at BIGINT NOT NULL, blocked_until BIGINT NOT NULL, "
                    + "updated_at BIGINT NOT NULL)");
        }
        clock = new MutableClock(1_700_000_000_000L);
        properties = new AdminSecurityProperties(
                "0123456789abcdef0123456789abcdef",
                7_200L,
                false,
                "Lax",
                3,
                900L,
                1L,
                8L);
    }

    @Test
    void accountBackoffIsSharedAcrossServerInstances() {
        AdminLoginGuard serverOne = new AdminLoginGuard(dataSource, properties, clock);
        assertTrue(serverOne.recordFailure("admin", "10.0.0.1").allowed());
        assertTrue(serverOne.recordFailure("admin", "10.0.0.2").allowed());

        AdminLoginGuard.Decision blocked = serverOne.recordFailure("admin", "10.0.0.3");
        assertFalse(blocked.allowed());
        assertEquals(1L, blocked.retryAfterSeconds());

        AdminLoginGuard serverTwo = new AdminLoginGuard(dataSource, properties, clock);
        assertFalse(serverTwo.check("admin", "10.0.0.99").allowed());
        assertTrue(serverTwo.check("normal-user", "10.0.0.99").allowed());

        clock.advanceMillis(1_001L);
        assertTrue(serverTwo.check("admin", "10.0.0.99").allowed());
        assertEquals(2_000L,
                serverTwo.recordFailure("admin", "10.0.0.99").retryAfterMs());
    }

    @Test
    void ipBackoffCoversCredentialSpraying() {
        AdminLoginGuard guard = new AdminLoginGuard(dataSource, properties, clock);
        assertTrue(guard.recordFailure("user-a", "10.0.0.8").allowed());
        assertTrue(guard.recordFailure("user-b", "10.0.0.8").allowed());
        assertFalse(guard.recordFailure("user-c", "10.0.0.8").allowed());
        assertFalse(guard.check("unseen-user", "10.0.0.8").allowed());
    }

    private static class MutableClock extends Clock {
        private final AtomicLong millis;

        private MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void advanceMillis(long value) {
            millis.addAndGet(value);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
        @Override public long millis() { return millis.get(); }
    }
}
