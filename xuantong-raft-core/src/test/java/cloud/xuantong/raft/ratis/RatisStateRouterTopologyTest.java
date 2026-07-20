package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RatisStateRouterTopologyTest {
    @Test
    void replacesPeerTopologyWithoutReplacingStateGroupSet() throws Exception {
        StateGroupId config = StateGroupId.config("config-default");
        RatisGroupDefinition initial = new RatisGroupDefinition(config, List.of(
                new RatisPeerDefinition("state-1", "127.0.0.1", 9101)));
        RatisGroupDefinition replacement = new RatisGroupDefinition(config, List.of(
                new RatisPeerDefinition("state-2", "127.0.0.1", 9102)));

        try (RatisStateRouter router = new RatisStateRouter(
                List.of(initial), Duration.ofSeconds(1), 1)) {
            assertDoesNotThrow(() -> router.replaceGroups(List.of(replacement)));
            assertThrows(IllegalArgumentException.class,
                    () -> router.replaceGroups(List.of(new RatisGroupDefinition(
                            StateGroupId.registry("registry-default"),
                            replacement.peers()))));
        }
    }

    @Test
    void writeAdmissionRejectsBeforeOpeningARaftClient() throws Exception {
        StateGroupId config = StateGroupId.config("config-default");
        RatisGroupDefinition group = new RatisGroupDefinition(config, List.of(
                new RatisPeerDefinition("state-1", "127.0.0.1", 9101)));
        AtomicInteger checks = new AtomicInteger();
        StateAccessException rejected = StateAccessException.retryable(
                StateFailureCode.STORAGE_EXHAUSTED,
                config,
                "storage watermark reached",
                StateCommitStatus.NOT_COMMITTED,
                null);

        try (RatisStateRouter router = new RatisStateRouter(
                List.of(group), Duration.ofSeconds(1), 1, groupId -> {
                    checks.incrementAndGet();
                    throw rejected;
                })) {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> router.submit(new StateCommand(
                                    config, "op-storage-1", "test.write", 1, new byte[0]))
                            .join());

            assertEquals(rejected, failure.getCause());
            assertEquals(1, checks.get());
        }
    }
}
