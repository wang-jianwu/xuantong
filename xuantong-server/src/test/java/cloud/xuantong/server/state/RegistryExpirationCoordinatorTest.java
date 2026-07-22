package cloud.xuantong.server.state;

import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryOverview;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryExpirationCoordinatorTest {
    @Test
    void writesNoExpirationCommandsWhileEmptyAndWakesAfterRegistration()
            throws Exception {
        StateGroupId groupId = StateGroupId.registry("registry-default");
        AtomicLong activeInstances = new AtomicLong();
        AtomicLong expirationCommands = new AtomicLong();
        StateClient stateClient = new RecordingStateClient(
                groupId, activeInstances, expirationCommands);
        RegistryStatePlaneProperties properties = new RegistryStatePlaneProperties(
                true, groupId.value(), 3_000L, 120_000L);
        RegistryExpirationCoordinator coordinator = new RegistryExpirationCoordinator(
                () -> true,
                stateClient,
                properties,
                groupId,
                RegistryActor.system("state-1"));

        coordinator.start();
        try {
            Thread.sleep(650L);
            assertEquals(0L, expirationCommands.get());

            activeInstances.set(1L);
            coordinator.wakeUp();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (expirationCommands.get() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(expirationCommands.get() > 0L);
        } finally {
            coordinator.close();
            stateClient.close();
        }
    }

    private static final class RecordingStateClient implements StateClient {
        private final StateGroupId groupId;
        private final AtomicLong activeInstances;
        private final AtomicLong expirationCommands;

        private RecordingStateClient(
                StateGroupId groupId,
                AtomicLong activeInstances,
                AtomicLong expirationCommands) {
            this.groupId = groupId;
            this.activeInstances = activeInstances;
            this.expirationCommands = expirationCommands;
        }

        @Override
        public CompletionStage<ApplyResult> submit(StateCommand command) {
            long index = expirationCommands.incrementAndGet();
            return CompletableFuture.completedFuture(new ApplyResult(
                    groupId,
                    command.operationId(),
                    ApplyStatus.APPLIED,
                    index,
                    RegistryStateCodec.RESULT_MUTATION,
                    new byte[0],
                    List.of()));
        }

        @Override
        public CompletionStage<QueryResult> query(StateQuery query) {
            long active = activeInstances.get();
            RegistryOverview overview = new RegistryOverview(
                    0L,
                    System.currentTimeMillis(),
                    active,
                    active == 0L ? 0L : 1L);
            return CompletableFuture.completedFuture(new QueryResult(
                    groupId,
                    0L,
                    false,
                    RegistryStateCodec.RESULT_OVERVIEW,
                    RegistryStateCodec.encodeOverview(overview),
                    List.of()));
        }

        @Override
        public CompletionStage<WatchBatch> watch(WatchRequest request) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("watch is not used"));
        }

        @Override
        public void close() {
        }
    }
}
