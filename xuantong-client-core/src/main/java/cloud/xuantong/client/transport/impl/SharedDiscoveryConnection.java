package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.transport.DiscoveryTransport;

import java.util.List;

/**
 * One discovery control-plane connection shared by service-scoped agents.
 * Each service keeps an independent Snapshot/cursor/Watch while all streams
 * reuse the same active Socket.D Session and Gateway failover state.
 */
public final class SharedDiscoveryConnection implements AutoCloseable {
    private final SocketDTransport controlTransport;
    private final long leaseTtlMs;
    private ConnectionScope connectedScope;
    private boolean closed;

    public SharedDiscoveryConnection(
            ClientIdentity identity,
            ControlPlaneOptions options,
            long leaseTtlMs) {
        if (leaseTtlMs < 1_000L) {
            throw new IllegalArgumentException("leaseTtlMs must be at least 1000");
        }
        this.controlTransport = SocketDTransport.forDiscovery(identity, options);
        this.leaseTtlMs = leaseTtlMs;
    }

    public synchronized DiscoveryTransport newServiceTransport() {
        if (closed) {
            throw new IllegalStateException("Shared discovery connection is closed");
        }
        return new SocketDDiscoveryTransport(this, leaseTtlMs);
    }

    synchronized void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken) {
        if (closed) {
            throw new IllegalStateException("Shared discovery connection is closed");
        }
        ConnectionScope requested = new ConnectionScope(
                normalizeAddresses(serverAddresses),
                requireName("namespace", namespace),
                requireName("group", group),
                accessToken == null ? "" : accessToken.trim());
        if (connectedScope == null) {
            controlTransport.connect(
                    requested.serverAddresses(),
                    requested.namespace(),
                    requested.group(),
                    requested.accessToken());
            connectedScope = requested;
            return;
        }
        if (!connectedScope.equals(requested)) {
            throw new IllegalArgumentException(
                    "All service agents sharing one discovery connection must use "
                            + "the same addresses, namespace, group and access token");
        }
    }

    SocketDTransport controlTransport() {
        return controlTransport;
    }

    private List<String> normalizeAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be empty");
        }
        return addresses.stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
    }

    private String requireName(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        connectedScope = null;
        controlTransport.close();
    }

    private record ConnectionScope(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken) {
    }
}
