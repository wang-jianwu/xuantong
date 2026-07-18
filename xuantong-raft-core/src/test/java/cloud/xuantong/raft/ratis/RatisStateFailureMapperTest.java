package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.protocol.exceptions.AlreadyClosedException;
import org.apache.ratis.protocol.exceptions.TimeoutIOException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisStateFailureMapperTest {
    private final StateGroupId groupId = StateGroupId.config("config-default");

    @Test
    void knownLeaderWriteTimeoutMapsToNoQuorumAndUnknownCommit() {
        StateAccessException failure = RatisStateFailureMapper.map(
                new CompletionException(new TimeoutIOException("proposal timeout")),
                groupId,
                true,
                true);

        assertEquals(StateFailureCode.NO_QUORUM, failure.code());
        assertEquals(StateCommitStatus.UNKNOWN, failure.commitStatus());
        assertTrue(failure.retryable());
    }

    @Test
    void closedAsyncClientMapsToStateUnavailable() {
        AlreadyClosedException closed = new AlreadyClosedException("ordered client closed");
        StateAccessException failure = RatisStateFailureMapper.map(
                closed,
                groupId,
                false,
                false);

        assertEquals(StateFailureCode.STATE_UNAVAILABLE, failure.code());
        assertEquals(StateCommitStatus.NOT_APPLICABLE, failure.commitStatus());
        assertTrue(failure.retryable());
        assertTrue(RatisStateFailureMapper.requiresClientRebuild(closed));
    }

    @Test
    void localCodecFailureIsNonRetryableInternalError() {
        StateAccessException failure = RatisStateFailureMapper.map(
                new RatisOperationException("invalid apply response"),
                groupId,
                true,
                true);

        assertEquals(StateFailureCode.INTERNAL_ERROR, failure.code());
        assertEquals(StateCommitStatus.UNKNOWN, failure.commitStatus());
        assertFalse(failure.retryable());
        assertFalse(RatisStateFailureMapper.requiresClientRebuild(failure));
    }
}
