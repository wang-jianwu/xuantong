package cloud.xuantong.server.state.management;

import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigSnapshot;
import cloud.xuantong.config.state.ConfigSnapshotRequest;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.ResolveConfigOperationRequest;
import cloud.xuantong.config.state.ResolvedConfigOperation;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionException;

@Component
public final class RatisConfigStateAccess implements ConfigStateAccess {
    @Inject
    private ControlStatePlaneRuntime runtime;
    @Inject
    private ConfigStatePlaneProperties properties;

    @Override
    public boolean available() {
        return runtime.isRunning();
    }

    @Override
    public StateGroupId groupId() {
        return properties.stateGroupId();
    }

    @Override
    public ReleaseDecision currentDecision(ConfigKey key) {
        requireAvailable();
        QueryResult result = join(runtime.stateClient().query(ConfigStateCodec.snapshotQuery(
                groupId(), new ConfigSnapshotRequest(List.of(key)), ReadOptions.linearizable())));
        if (!ConfigStateCodec.RESULT_SNAPSHOT.equals(result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Config State snapshot result: " + result.resultType());
        }
        try {
            ConfigSnapshot snapshot = ConfigStateCodec.decodeSnapshot(result.payload());
            return snapshot.decisions().isEmpty() ? null : snapshot.decisions().getFirst();
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Config State snapshot result", e);
        }
    }

    @Override
    public ApplyResult submit(StateCommand command) {
        requireAvailable();
        return join(runtime.stateClient().submit(command));
    }

    @Override
    public ResolvedConfigOperation resolve(ConfigActor actor, String operationId) {
        requireAvailable();
        QueryResult result = join(runtime.stateClient().query(
                ConfigStateCodec.resolveOperationQuery(
                        groupId(),
                        new ResolveConfigOperationRequest(actor, operationId),
                        ReadOptions.linearizable())));
        if (!ConfigStateCodec.RESULT_RESOLVED_OPERATION.equals(result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Config operation result: " + result.resultType());
        }
        try {
            return ConfigStateCodec.decodeResolvedOperation(result.payload());
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Config operation result", e);
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException(
                    "Config State Plane is disabled or unavailable");
        }
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Config State request failed", cause);
        }
    }
}
