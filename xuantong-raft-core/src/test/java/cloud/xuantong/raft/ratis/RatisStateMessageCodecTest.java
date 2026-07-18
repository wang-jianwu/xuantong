package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RatisStateMessageCodecTest {
    private final StateGroupId groupId = StateGroupId.config("config-default");

    @Test
    void roundTripsCommandAndApplyResult() throws Exception {
        StateCommand command = new StateCommand(
                groupId, "op-1", "config.publish", 2, new byte[]{1, 2});
        StateCommand decodedCommand = RatisStateMessageCodec.decodeCommand(
                RatisStateMessageCodec.encodeCommand(command));

        assertEquals(command.groupId(), decodedCommand.groupId());
        assertEquals(command.operationId(), decodedCommand.operationId());
        assertArrayEquals(command.payload(), decodedCommand.payload());

        ApplyResult result = new ApplyResult(
                groupId,
                "op-1",
                ApplyStatus.APPLIED,
                9,
                "config.publish-result",
                new byte[]{3},
                List.of(
                        StateRevision.configDecision(groupId, "demo.yml", 4),
                        StateRevision.configEvent(groupId, 7)));
        ApplyResult decodedResult = RatisStateMessageCodec.decodeApplyResult(
                RatisStateMessageCodec.encodeApplyResult(result));

        assertEquals(result.appliedIndex(), decodedResult.appliedIndex());
        assertEquals(result.revisions(), decodedResult.revisions());
        assertArrayEquals(result.payload(), decodedResult.payload());
    }

    @Test
    void roundTripsFilteredWatchCoverage() throws Exception {
        StateRevision after = StateRevision.configEvent(groupId, 10);
        WatchBatch batch = new WatchBatch(
                after,
                StateRevision.configEvent(groupId, 15),
                StateRevision.configEvent(groupId, 8),
                false,
                List.of(new WatchEvent(
                        StateRevision.configEvent(groupId, 13),
                        "config.invalidated",
                        1,
                        new byte[]{4})));

        WatchBatch decoded = RatisStateMessageCodec.decodeWatchBatch(
                RatisStateMessageCodec.encodeWatchBatch(batch));

        assertEquals(15, decoded.coveredThrough().value());
        assertEquals(13, decoded.events().getFirst().revision().value());
    }

    @Test
    void rejectsWrongMessageTypeAndTrailingBytes() throws Exception {
        byte[] query = RatisStateMessageCodec.encodeQuery(new StateQuery(
                groupId,
                "config.fetch",
                1,
                new byte[0],
                ReadOptions.linearizable()));
        assertThrows(IOException.class,
                () -> RatisStateMessageCodec.decodeCommand(query));

        byte[] command = RatisStateMessageCodec.encodeCommand(new StateCommand(
                groupId, "op-2", "config.publish", 1, new byte[0]));
        byte[] withTrailingByte = Arrays.copyOf(command, command.length + 1);
        assertThrows(IOException.class,
                () -> RatisStateMessageCodec.decodeCommand(withTrailingByte));
    }
}
