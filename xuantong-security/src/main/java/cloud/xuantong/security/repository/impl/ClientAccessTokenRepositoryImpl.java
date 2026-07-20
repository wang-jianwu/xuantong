package cloud.xuantong.security.repository.impl;
import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.ClientAccessToken;
import cloud.xuantong.security.repository.ClientAccessTokenRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
@Component
public class ClientAccessTokenRepositoryImpl implements ClientAccessTokenRepository {
    @Db private EasyEntityQuery easyQuery;
    @Inject private DataSource dataSource;
    public ClientAccessTokenRepositoryImpl() {
    }
    public ClientAccessTokenRepositoryImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }
    public ClientAccessToken find(Long id) { return easyQuery.queryable(ClientAccessToken.class).whereById(id).firstOrNull(); }
    public ClientAccessToken findByHash(String hash) { return easyQuery.queryable(ClientAccessToken.class).where(o -> o.tokenHash().eq(hash)).firstOrNull(); }
    public List<ClientAccessToken> findAll() { return easyQuery.queryable(ClientAccessToken.class).orderBy(o -> o.id().desc()).toList(); }
    public PageResult<ClientAccessToken> findPage(String keyword, Boolean active, PageQuery pageQuery) {
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        var result = easyQuery.queryable(ClientAccessToken.class)
                .where(o -> {
                    o.isActive().eq(active != null, active);
                    if (normalizedKeyword != null) {
                        o.or(() -> {
                            o.tokenName().contains(normalizedKeyword);
                            o.tenant().contains(normalizedKeyword);
                            o.namespaceId().contains(normalizedKeyword);
                            o.groupName().contains(normalizedKeyword);
                            o.createdBy().contains(normalizedKeyword);
                        });
                    }
                })
                .orderBy(o -> o.id().desc())
                .toPageResult(pageQuery.page(), pageQuery.pageSize());
        return PageResult.of(pageQuery, result.getTotal(), result.getData());
    }
    public long save(ClientAccessToken token) { return easyQuery.insertable(token).executeRows(true); }
    public long revoke(Long id) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String tokenHash = activeTokenHashForUpdate(connection, id);
                if (tokenHash == null) {
                    connection.rollback();
                    return 0L;
                }
                long updated;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE client_access_token SET is_active = FALSE "
                                + "WHERE id = ? AND is_active = TRUE")) {
                    statement.setLong(1, id);
                    updated = statement.executeUpdate();
                }
                if (updated == 1L) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO credential_revocation_event "
                                    + "(token_hash, revoked_at) VALUES (?, ?)")) {
                        statement.setString(1, tokenHash);
                        statement.setLong(2, System.currentTimeMillis());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return updated;
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to revoke access token and append revocation event", e);
        }
    }
    public long countActive() { return easyQuery.queryable(ClientAccessToken.class).where(o -> o.isActive().eq(true)).count(); }

    private String activeTokenHashForUpdate(Connection connection, Long id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT token_hash FROM client_access_token "
                        + "WHERE id = ? AND is_active = TRUE FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }
}
