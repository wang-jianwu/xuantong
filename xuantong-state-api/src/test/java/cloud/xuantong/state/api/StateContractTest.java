package cloud.xuantong.state.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateContractTest {
    private final StateGroupId configGroup = StateGroupId.config("config-default");
    private final StateGroupId registryGroup = StateGroupId.registry("registry-default");

    @Test
    void commandRequiresOperationIdAndDefensivelyCopiesPayload() {
        byte[] payload = {1, 2, 3};
        StateCommand command = new StateCommand(
                configGroup, "op-1", "config.publish", 1, payload);
        payload[0] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, command.payload());
        byte[] returned = command.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, command.payload());
        assertThrows(IllegalArgumentException.class, () -> new StateCommand(
                configGroup, " ", "config.publish", 1, new byte[0]));
    }

    @Test
    void revisionCoordinatesCannotBeMixed() {
        StateRevision decision = StateRevision.configDecision(
                configGroup, "public/DEFAULT_GROUP/demo.yml", 7);
        StateRevision event = StateRevision.configEvent(configGroup, 7);

        assertFalse(decision.sameCoordinate(event));
        assertThrows(IllegalArgumentException.class, () -> decision.compareTo(event));
        assertThrows(IllegalArgumentException.class,
                () -> StateRevision.registry(configGroup, 1));
        assertEquals(8, decision.next().value());
    }

    @Test
    void boundedStaleReadRequiresBothRevisionAndPositiveBound() {
        StateRevision minimum = StateRevision.registry(registryGroup, 12);
        ReadOptions options = ReadOptions.boundedStale(
                minimum, Duration.ofSeconds(2));

        assertEquals(ReadConsistency.BOUNDED_STALE, options.consistency());
        assertThrows(IllegalArgumentException.class, () -> new ReadOptions(
                ReadConsistency.BOUNDED_STALE, null, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new ReadOptions(
                ReadConsistency.BOUNDED_STALE, minimum, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new StateQuery(
                configGroup, "config.fetch", 1, new byte[0], options));
    }

    @Test
    void watchBatchProvesCoverageEvenWhenFilteredEventsAreSkipped() {
        StateRevision after = StateRevision.registry(registryGroup, 10);
        WatchBatch batch = new WatchBatch(
                after,
                StateRevision.registry(registryGroup, 15),
                StateRevision.registry(registryGroup, 8),
                false,
                List.of(new WatchEvent(
                        StateRevision.registry(registryGroup, 13),
                        "registry.instance-updated",
                        1,
                        new byte[]{4})));

        assertEquals(15, batch.coveredThrough().value());
        assertEquals(13, batch.events().getFirst().revision().value());
    }

    @Test
    void compactedWatchRequiresAnEmptyResetBatch() {
        StateRevision after = StateRevision.configEvent(configGroup, 4);
        WatchBatch reset = new WatchBatch(
                after,
                StateRevision.configEvent(configGroup, 20),
                StateRevision.configEvent(configGroup, 8),
                true,
                List.of());

        assertTrue(reset.resetRequired());
        assertThrows(IllegalArgumentException.class, () -> new WatchBatch(
                after,
                StateRevision.configEvent(configGroup, 20),
                StateRevision.configEvent(configGroup, 8),
                false,
                List.of()));
    }

    @Test
    void structuredFailurePreservesRevisionAndCommitAmbiguity() {
        StateRevision observed = StateRevision.registry(registryGroup, 18);
        StateAccessException failure = new StateAccessException(
                StateFailureCode.STALE_REPLICA,
                registryGroup,
                "replica is behind",
                true,
                25,
                observed,
                StateCommitStatus.NOT_APPLICABLE,
                null);

        assertEquals(StateFailureCode.STALE_REPLICA, failure.code());
        assertEquals(observed, failure.observedRevision());
        assertEquals(25, failure.retryAfterMs());
        assertThrows(IllegalArgumentException.class, () -> new StateAccessException(
                StateFailureCode.STALE_REPLICA,
                registryGroup,
                "wrong revision",
                true,
                0,
                StateRevision.configEvent(configGroup, 1),
                StateCommitStatus.NOT_APPLICABLE,
                null));
    }

    @Test
    void rejectedWriteCanProveThatItWasNotCommitted() {
        StateAccessException failure = StateAccessException.retryable(
                StateFailureCode.STORAGE_EXHAUSTED,
                configGroup,
                "storage watermark reached",
                StateCommitStatus.NOT_COMMITTED,
                null);

        assertEquals(StateFailureCode.STORAGE_EXHAUSTED, failure.code());
        assertEquals(StateCommitStatus.NOT_COMMITTED, failure.commitStatus());
        assertTrue(failure.retryable());
    }
}
