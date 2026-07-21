package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadConsistency;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.TimeDuration;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class RatisStateClient implements StateClient {
    private final RatisGroupDefinition group;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private RaftPeerId leaderHint;
    private RaftClient client;
    private boolean closed;

    public RatisStateClient(
            RatisGroupDefinition group,
            Duration requestTimeout,
            int maxAttempts) {
        this(group, requestTimeout, maxAttempts, null);
    }

    public RatisStateClient(
            RatisGroupDefinition group,
            Duration requestTimeout,
            int maxAttempts,
            String initialLeaderId) {
        if (group == null) {
            throw new IllegalArgumentException("group must not be null");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.group = group;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = maxAttempts;
        if (initialLeaderId != null && !initialLeaderId.isBlank()) {
            group.requirePeer(initialLeaderId);
            this.leaderHint = RaftPeerId.valueOf(initialLeaderId);
        }
        this.client = createClient();
    }

    private RaftClient createClient() {
        RaftProperties properties = new RaftProperties();
        RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
        RaftClientConfigKeys.Rpc.setRequestTimeout(properties,
                duration(requestTimeout));
        RaftClient.Builder builder = RaftClient.newBuilder()
                .setRaftGroup(group.toRaftGroup())
                .setProperties(properties)
                .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                        maxAttempts, TimeDuration.valueOf(100, TimeUnit.MILLISECONDS)));
        if (leaderHint != null) {
            builder.setLeaderId(leaderHint);
        }
        return builder.build();
    }

    public RatisResult write(byte[] command) throws IOException {
        RaftClient selected = client();
        try {
            return requireSuccess(selected.io().send(message(command)));
        } catch (IOException | RuntimeException e) {
            invalidateIfBroken(selected, e);
            throw e;
        }
    }

    public CompletableFuture<RatisResult> writeAsync(byte[] command) {
        RaftClient selected = null;
        try {
            selected = client();
            return invalidateBrokenClientOnFailure(
                    selected,
                    selected.async().send(message(command)).thenApply(reply -> {
                        try {
                            return requireSuccess(reply);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }));
        } catch (RuntimeException e) {
            if (selected != null) {
                invalidateIfBroken(selected, e);
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<ApplyResult> submit(StateCommand command) {
        RaftClient selected = null;
        try {
            requireGroup(command.groupId());
            byte[] encoded = RatisStateMessageCodec.encodeCommand(command);
            selected = client();
            return mapFailures(
                    selected.async().send(message(encoded))
                            .thenApply(reply -> decodeApplyReply(command, reply)),
                    command.groupId(),
                    true,
                    selected);
        } catch (Exception e) {
            if (selected != null) {
                invalidateIfBroken(selected, e);
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<QueryResult> query(StateQuery query) {
        RaftClient selected = null;
        try {
            requireGroup(query.groupId());
            requireLinearizable(query.readOptions().consistency());
            byte[] encoded = RatisStateMessageCodec.encodeQuery(query);
            selected = client();
            return mapFailures(
                    selected.async().sendReadOnly(message(encoded))
                            .thenApply(this::decodeQueryReply),
                    query.groupId(),
                    false,
                    selected);
        } catch (Exception e) {
            if (selected != null) {
                invalidateIfBroken(selected, e);
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<WatchBatch> watch(WatchRequest request) {
        RaftClient selected = null;
        try {
            requireGroup(request.groupId());
            requireLinearizable(request.readOptions().consistency());
            byte[] encoded = RatisStateMessageCodec.encodeWatchRequest(request);
            selected = client();
            return mapFailures(
                    selected.async().sendReadOnly(message(encoded))
                            .thenApply(this::decodeWatchReply),
                    request.groupId(),
                    false,
                    selected);
        } catch (Exception e) {
            if (selected != null) {
                invalidateIfBroken(selected, e);
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    public RatisResult linearizableRead(byte[] query) throws IOException {
        RaftClient selected = client();
        try {
            return requireSuccess(selected.io().sendReadOnly(message(query)));
        } catch (IOException | RuntimeException e) {
            invalidateIfBroken(selected, e);
            throw e;
        }
    }

    public RatisResult linearizableRead(byte[] query, String nodeId) throws IOException {
        group.requirePeer(nodeId);
        RaftClient selected = client();
        try {
            return requireSuccess(selected.io().sendReadOnly(
                    message(query), RaftPeerId.valueOf(nodeId)));
        } catch (IOException | RuntimeException e) {
            invalidateIfBroken(selected, e);
            throw e;
        }
    }

    public RatisResult forceSnapshot(Duration timeout) throws IOException {
        return forceSnapshot(timeout, null);
    }

    public RatisResult forceSnapshot(Duration timeout, String nodeId) throws IOException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (nodeId != null) {
            group.requirePeer(nodeId);
        }
        RaftClient selected = client();
        var snapshotApi = nodeId == null
                ? selected.getSnapshotManagementApi()
                : selected.getSnapshotManagementApi(RaftPeerId.valueOf(nodeId));
        try {
            return requireSuccess(snapshotApi.create(true, timeout.toMillis()));
        } catch (IOException | RuntimeException e) {
            invalidateIfBroken(selected, e);
            throw e;
        }
    }

    public RatisStateNodeCapability capability(String nodeId) throws IOException {
        group.requirePeer(nodeId);
        RatisResult result = linearizableRead(
                RatisStateMessageCodec.encodeCapabilityRequest(group.groupId()), nodeId);
        return RatisStateMessageCodec.decodeCapabilityResponse(
                result.serverId(), result.payload());
    }

    private RatisResult requireSuccess(RaftClientReply reply) throws IOException {
        if (reply == null) {
            throw new RatisOperationException("Raft operation returned no reply");
        }
        if (!reply.isSuccess()) {
            throw new RatisOperationException(
                    "Raft operation failed: " + reply,
                    reply.getException());
        }
        byte[] payload = reply.getMessage() == null
                ? new byte[0]
                : reply.getMessage().getContent().toByteArray();
        return new RatisResult(payload, reply.getServerId().toString(), reply.getLogIndex());
    }

    private ApplyResult decodeApplyReply(
            StateCommand command, RaftClientReply reply) {
        try {
            RatisResult raw = requireSuccess(reply);
            ApplyResult result = RatisStateMessageCodec.decodeApplyResult(raw.payload());
            requireGroup(result.groupId());
            if (!command.operationId().equals(result.operationId())) {
                throw new RatisOperationException(
                        "Raft apply reply operationId does not match request");
            }
            if (raw.logIndex() > 0 && raw.logIndex() != result.appliedIndex()) {
                throw new RatisOperationException(
                        "Raft apply reply index does not match state-machine result");
            }
            return result;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private QueryResult decodeQueryReply(RaftClientReply reply) {
        try {
            QueryResult result = RatisStateMessageCodec.decodeQueryResult(
                    requireSuccess(reply).payload());
            requireGroup(result.groupId());
            if (result.stale()) {
                throw new RatisOperationException(
                        "Linearizable state query returned a stale result");
            }
            return result;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private WatchBatch decodeWatchReply(RaftClientReply reply) {
        try {
            WatchBatch result = RatisStateMessageCodec.decodeWatchBatch(
                    requireSuccess(reply).payload());
            requireGroup(result.groupId());
            return result;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private void requireGroup(StateGroupId groupId) {
        if (!group.groupId().equals(groupId)) {
            throw new IllegalArgumentException("State request targets " + groupId
                    + " but this client is bound to " + group.groupId());
        }
    }

    private static void requireLinearizable(ReadConsistency consistency)
            throws RatisOperationException {
        if (consistency != ReadConsistency.LINEARIZABLE) {
            throw new RatisOperationException(
                    "BOUNDED_STALE requires a follower freshness tracker and is not enabled yet");
        }
    }

    private <T> CompletableFuture<T> mapFailures(
            CompletableFuture<T> source,
            StateGroupId groupId,
            boolean write,
            RaftClient selected) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                mapped.complete(value);
                return;
            }
            boolean knownLeader = selected.getLeaderId() != null;
            invalidateIfBroken(selected, failure);
            mapped.completeExceptionally(RatisStateFailureMapper.map(
                    failure, groupId, write, knownLeader));
        });
        return mapped;
    }

    private <T> CompletableFuture<T> invalidateBrokenClientOnFailure(
            RaftClient selected,
            CompletableFuture<T> source) {
        CompletableFuture<T> guarded = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                guarded.complete(value);
            } else {
                invalidateIfBroken(selected, failure);
                guarded.completeExceptionally(failure);
            }
        });
        return guarded;
    }

    private synchronized RaftClient client() {
        if (closed) {
            throw new IllegalStateException(
                    "State client is closed for " + group.groupId());
        }
        if (client == null) {
            client = createClient();
        }
        return client;
    }

    private void invalidateIfBroken(RaftClient expected, Throwable failure) {
        if (!RatisStateFailureMapper.requiresClientRebuild(failure)) {
            return;
        }
        RaftClient retired;
        synchronized (this) {
            if (client != expected) {
                return;
            }
            RaftPeerId observedLeader = expected.getLeaderId();
            if (observedLeader != null) {
                leaderHint = observedLeader;
            }
            retired = client;
            client = null;
        }
        try {
            retired.close();
        } catch (IOException ignored) {
        }
    }

    private Message message(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        return Message.valueOf(ByteString.copyFrom(payload));
    }

    private static TimeDuration duration(Duration duration) {
        return TimeDuration.valueOf(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        RaftClient retired;
        synchronized (this) {
            closed = true;
            retired = client;
            client = null;
        }
        if (retired != null) {
            retired.close();
        }
    }
}
