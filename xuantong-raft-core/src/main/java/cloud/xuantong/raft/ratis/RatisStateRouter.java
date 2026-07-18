package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Routes each State operation to exactly one Raft Group client. */
public final class RatisStateRouter implements StateClient {
    private final Map<StateGroupId, ClientSlot> clients;

    public RatisStateRouter(
            Collection<RatisGroupDefinition> groups,
            Duration requestTimeout,
            int maxAttempts) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("groups must not be empty");
        }
        if (requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Map<StateGroupId, ClientSlot> created = new LinkedHashMap<>();
        try {
            for (RatisGroupDefinition group : groups) {
                ClientSlot slot = new ClientSlot(group, requestTimeout, maxAttempts);
                if (created.putIfAbsent(group.groupId(), slot) != null) {
                    slot.close();
                    throw new IllegalArgumentException(
                            "Duplicate State Group: " + group.groupId());
                }
            }
        } catch (Exception e) {
            closeAll(created.values(), e);
            throw e instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Failed to create State router", e);
        }
        this.clients = Map.copyOf(created);
    }

    public Set<StateGroupId> groups() {
        return clients.keySet();
    }

    @Override
    public CompletableFuture<ApplyResult> submit(StateCommand command) {
        ClientSlot slot = clients.get(command.groupId());
        return slot == null
                ? unknownGroup(command.groupId())
                : slot.execute(client -> client.submit(command));
    }

    @Override
    public CompletableFuture<QueryResult> query(StateQuery query) {
        ClientSlot slot = clients.get(query.groupId());
        return slot == null
                ? unknownGroup(query.groupId())
                : slot.execute(client -> client.query(query));
    }

    @Override
    public CompletableFuture<WatchBatch> watch(WatchRequest request) {
        ClientSlot slot = clients.get(request.groupId());
        return slot == null
                ? unknownGroup(request.groupId())
                : slot.execute(client -> client.watch(request));
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (ClientSlot client : clients.values()) {
            try {
                client.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static <T> CompletableFuture<T> unknownGroup(StateGroupId groupId) {
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown State Group: " + groupId));
    }

    private static void closeAll(
            Collection<ClientSlot> clients, Exception original) {
        for (ClientSlot client : clients) {
            try {
                client.close();
            } catch (IOException closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
    }

    private static final class ClientSlot implements AutoCloseable {
        private final RatisGroupDefinition group;
        private final Duration requestTimeout;
        private final int maxAttempts;
        private RatisStateClient client;
        private boolean closed;

        private ClientSlot(
                RatisGroupDefinition group,
                Duration requestTimeout,
                int maxAttempts) {
            this.group = group;
            this.requestTimeout = requestTimeout;
            this.maxAttempts = maxAttempts;
        }

        private <T> CompletableFuture<T> execute(
                Function<RatisStateClient, CompletableFuture<T>> operation) {
            RatisStateClient selected;
            try {
                selected = client();
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            CompletableFuture<T> result;
            try {
                result = operation.apply(selected);
            } catch (RuntimeException e) {
                invalidate(selected);
                return CompletableFuture.failedFuture(e);
            }
            return result;
        }

        private synchronized RatisStateClient client() {
            if (closed) {
                throw new IllegalStateException(
                        "State router is closed for " + group.groupId());
            }
            if (client == null) {
                client = new RatisStateClient(group, requestTimeout, maxAttempts);
            }
            return client;
        }

        private synchronized void invalidate(RatisStateClient expected) {
            if (client != expected) {
                return;
            }
            client = null;
            try {
                expected.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            if (client != null) {
                try {
                    client.close();
                } finally {
                    client = null;
                }
            }
        }
    }
}
