package cloud.xuantong.server.admin.security;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Component
public class AdminLoginGuard {
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    @Inject
    private DataSource dataSource;
    @Inject
    private AdminSecurityProperties properties;

    private Clock clock = Clock.systemUTC();

    public AdminLoginGuard() {
    }

    AdminLoginGuard(DataSource dataSource, AdminSecurityProperties properties, Clock clock) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.clock = clock;
    }

    public Decision check(String username, String remoteIp) {
        long now = clock.millis();
        long blockedUntil = 0L;
        try (Connection connection = dataSource.getConnection()) {
            for (String key : keys(username, remoteIp)) {
                GuardState state = find(connection, key, false);
                if (state != null) {
                    blockedUntil = Math.max(blockedUntil, state.blockedUntilEpochMs());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to check admin login guard", e);
        }
        return decision(now, blockedUntil);
    }

    public Decision recordFailure(String username, String remoteIp) {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    long now = clock.millis();
                    long blockedUntil = 0L;
                    for (String key : keys(username, remoteIp)) {
                        blockedUntil = Math.max(blockedUntil,
                                incrementFailure(connection, key, now).blockedUntilEpochMs());
                    }
                    prune(connection, now);
                    connection.commit();
                    return decision(now, blockedUntil);
                } catch (SQLException e) {
                    connection.rollback();
                    if (!isConstraintViolation(e) || attempt == MAX_TRANSACTION_ATTEMPTS) {
                        throw e;
                    }
                    last = e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                last = e;
                if (!isConstraintViolation(e) || attempt == MAX_TRANSACTION_ATTEMPTS) {
                    break;
                }
            }
        }
        throw new IllegalStateException("Unable to update admin login guard", last);
    }

    public void recordSuccess(String username) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM admin_login_guard WHERE guard_key = ?")) {
            statement.setString(1, accountKey(username));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to reset admin login guard", e);
        }
    }

    private GuardState incrementFailure(Connection connection, String key, long now)
            throws SQLException {
        GuardState current = find(connection, key, true);
        int failures;
        long windowStartedAt;
        if (current == null || now - current.windowStartedAtEpochMs()
                >= properties.loginWindowMillis()) {
            failures = 1;
            windowStartedAt = now;
        } else {
            failures = current.failureCount() + 1;
            windowStartedAt = current.windowStartedAtEpochMs();
        }
        long blockedUntil = now + backoffMillis(failures);
        if (current == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO admin_login_guard "
                            + "(guard_key, failure_count, window_started_at, blocked_until, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, key);
                statement.setInt(2, failures);
                statement.setLong(3, windowStartedAt);
                statement.setLong(4, blockedUntil);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE admin_login_guard SET failure_count = ?, window_started_at = ?, "
                            + "blocked_until = ?, updated_at = ? WHERE guard_key = ?")) {
                statement.setInt(1, failures);
                statement.setLong(2, windowStartedAt);
                statement.setLong(3, blockedUntil);
                statement.setLong(4, now);
                statement.setString(5, key);
                statement.executeUpdate();
            }
        }
        return new GuardState(failures, windowStartedAt, blockedUntil);
    }

    private GuardState find(Connection connection, String key, boolean forUpdate)
            throws SQLException {
        String sql = "SELECT failure_count, window_started_at, blocked_until "
                + "FROM admin_login_guard WHERE guard_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new GuardState(result.getInt(1), result.getLong(2), result.getLong(3))
                        : null;
            }
        }
    }

    private void prune(Connection connection, long now) throws SQLException {
        long retention = Math.max(properties.loginWindowMillis() * 4L, 86_400_000L);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM admin_login_guard WHERE updated_at < ?")) {
            statement.setLong(1, now - retention);
            statement.executeUpdate();
        }
    }

    long backoffMillis(int failureCount) {
        if (failureCount < properties.loginMaxFailures()) {
            return 0L;
        }
        int exponent = Math.min(30, failureCount - properties.loginMaxFailures());
        long multiplier = 1L << exponent;
        long base = properties.loginBaseBackoffMillis();
        if (multiplier > Long.MAX_VALUE / base) {
            return properties.loginMaxBackoffMillis();
        }
        return Math.min(properties.loginMaxBackoffMillis(), base * multiplier);
    }

    private Decision decision(long now, long blockedUntil) {
        if (blockedUntil <= now) {
            return Decision.allow();
        }
        long retryAfterMs = blockedUntil - now;
        return Decision.block(retryAfterMs);
    }

    private List<String> keys(String username, String remoteIp) {
        return List.of(accountKey(username), "ip:" + digest(normalize(remoteIp)));
    }

    private String accountKey(String username) {
        return "account:" + digest(normalize(username));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String digest(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean isConstraintViolation(SQLException error) {
        SQLException current = error;
        while (current != null) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private record GuardState(int failureCount, long windowStartedAtEpochMs,
                              long blockedUntilEpochMs) {
    }

    public record Decision(boolean allowed, long retryAfterMs) {
        static Decision allow() {
            return new Decision(true, 0L);
        }

        static Decision block(long retryAfterMs) {
            return new Decision(false, Math.max(1L, retryAfterMs));
        }

        public long retryAfterSeconds() {
            return Math.max(1L, (retryAfterMs + 999L) / 1_000L);
        }
    }
}
