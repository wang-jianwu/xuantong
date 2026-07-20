package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public final class JdbcGatewayClusterStore implements GatewayClusterStore {
    @Inject
    private DataSource dataSource;

    public JdbcGatewayClusterStore() {
    }

    JdbcGatewayClusterStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void publish(
            String clusterId,
            String gatewayId,
            String runtimeId,
            long nowEpochMs,
            long leaseExpiresAtEpochMs,
            GatewayRuntimeSnapshot snapshot) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                LeaseOwner owner = findOwnerForUpdate(connection, clusterId, gatewayId);
                if (owner == null) {
                    insert(connection, clusterId, gatewayId, runtimeId,
                            leaseExpiresAtEpochMs, snapshot);
                } else if (runtimeId.equals(owner.runtimeId())
                        || owner.leaseExpiresAtEpochMs() <= nowEpochMs) {
                    update(connection, clusterId, gatewayId, runtimeId,
                            leaseExpiresAtEpochMs, snapshot);
                } else {
                    throw new IllegalStateException(
                            "Gateway identity is already leased by another runtime: clusterId="
                                    + clusterId + ", gatewayId=" + gatewayId);
                }
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Failed to publish Gateway runtime snapshot", e);
        }
    }

    @Override
    public List<StoredGatewaySnapshot> findRecent(
            String clusterId, long minimumLeaseExpiryEpochMs) {
        String sql = "SELECT gateway_id, runtime_id, lease_expires_at, snapshot_payload "
                + "FROM gateway_runtime_snapshot WHERE cluster_id = ? "
                + "AND lease_expires_at >= ? ORDER BY gateway_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clusterId);
            statement.setLong(2, minimumLeaseExpiryEpochMs);
            try (ResultSet result = statement.executeQuery()) {
                List<StoredGatewaySnapshot> snapshots = new ArrayList<>();
                while (result.next()) {
                    snapshots.add(new StoredGatewaySnapshot(
                            clusterId,
                            result.getString("gateway_id"),
                            result.getString("runtime_id"),
                            result.getLong("lease_expires_at"),
                            ONode.deserialize(result.getString("snapshot_payload"),
                                    GatewayRuntimeSnapshot.class)));
                }
                return List.copyOf(snapshots);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Gateway runtime snapshots", e);
        }
    }

    @Override
    public long maxRevocationEventId() {
        String sql = "SELECT COALESCE(MAX(event_id), 0) FROM credential_revocation_event";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize credential revocation cursor", e);
        }
    }

    @Override
    public List<CredentialRevocation> findRevocationsAfter(long eventId, int limit) {
        String sql = "SELECT event_id, token_hash, revoked_at "
                + "FROM credential_revocation_event WHERE event_id > ? "
                + "ORDER BY event_id LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, eventId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CredentialRevocation> events = new ArrayList<>();
                while (result.next()) {
                    events.add(new CredentialRevocation(
                            result.getLong("event_id"),
                            result.getString("token_hash"),
                            result.getLong("revoked_at")));
                }
                return List.copyOf(events);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to poll credential revocations", e);
        }
    }

    @Override
    public void cleanup(long snapshotExpiryCutoffEpochMs, long revocationCutoffEpochMs) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement snapshots = connection.prepareStatement(
                     "DELETE FROM gateway_runtime_snapshot WHERE lease_expires_at < ?");
             PreparedStatement revocations = connection.prepareStatement(
                     "DELETE FROM credential_revocation_event WHERE revoked_at < ?")) {
            snapshots.setLong(1, snapshotExpiryCutoffEpochMs);
            snapshots.executeUpdate();
            revocations.setLong(1, revocationCutoffEpochMs);
            revocations.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clean Gateway cluster coordination data", e);
        }
    }

    private LeaseOwner findOwnerForUpdate(
            Connection connection, String clusterId, String gatewayId) throws SQLException {
        String sql = "SELECT runtime_id, lease_expires_at FROM gateway_runtime_snapshot "
                + "WHERE cluster_id = ? AND gateway_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clusterId);
            statement.setString(2, gatewayId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new LeaseOwner(result.getString(1), result.getLong(2)) : null;
            }
        }
    }

    private void insert(
            Connection connection,
            String clusterId,
            String gatewayId,
            String runtimeId,
            long leaseExpiresAtEpochMs,
            GatewayRuntimeSnapshot snapshot) throws SQLException {
        String sql = "INSERT INTO gateway_runtime_snapshot "
                + "(cluster_id, gateway_id, runtime_id, transport_generation, "
                + "snapshot_payload, captured_at, lease_expires_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindSnapshot(statement, clusterId, gatewayId, runtimeId,
                    leaseExpiresAtEpochMs, snapshot);
            statement.executeUpdate();
        }
    }

    private void update(
            Connection connection,
            String clusterId,
            String gatewayId,
            String runtimeId,
            long leaseExpiresAtEpochMs,
            GatewayRuntimeSnapshot snapshot) throws SQLException {
        String sql = "UPDATE gateway_runtime_snapshot SET runtime_id = ?, "
                + "transport_generation = ?, snapshot_payload = ?, captured_at = ?, "
                + "lease_expires_at = ?, updated_at = ? "
                + "WHERE cluster_id = ? AND gateway_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runtimeId);
            statement.setLong(2, snapshot.transportGeneration());
            statement.setString(3, ONode.serialize(snapshot));
            statement.setLong(4, snapshot.capturedAt());
            statement.setLong(5, leaseExpiresAtEpochMs);
            statement.setLong(6, snapshot.capturedAt());
            statement.setString(7, clusterId);
            statement.setString(8, gatewayId);
            statement.executeUpdate();
        }
    }

    private void bindSnapshot(
            PreparedStatement statement,
            String clusterId,
            String gatewayId,
            String runtimeId,
            long leaseExpiresAtEpochMs,
            GatewayRuntimeSnapshot snapshot) throws SQLException {
        statement.setString(1, clusterId);
        statement.setString(2, gatewayId);
        statement.setString(3, runtimeId);
        statement.setLong(4, snapshot.transportGeneration());
        statement.setString(5, ONode.serialize(snapshot));
        statement.setLong(6, snapshot.capturedAt());
        statement.setLong(7, leaseExpiresAtEpochMs);
        statement.setLong(8, snapshot.capturedAt());
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private record LeaseOwner(String runtimeId, long leaseExpiresAtEpochMs) {
    }
}
