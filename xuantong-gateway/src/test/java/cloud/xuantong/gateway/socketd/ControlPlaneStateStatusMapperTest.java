package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneStateStatusMapperTest {
    @Test
    void mapsStorageAdmissionFailureWithoutLosingCommitCertainty() {
        StateGroupId groupId = StateGroupId.config("config-default");
        StateAccessException failure = StateAccessException.retryable(
                StateFailureCode.STORAGE_EXHAUSTED,
                groupId,
                "storage watermark reached",
                StateCommitStatus.NOT_COMMITTED,
                null);

        ResponseStatus status = ControlPlaneStateStatusMapper.map(failure, groupId);

        assertEquals(ResponseCode.STORAGE_EXHAUSTED, status.getCode());
        assertEquals(CommitStatus.NOT_COMMITTED, status.getCommitStatus());
        assertEquals("config-default", status.getGroupId());
        assertTrue(status.getRetryable());
    }
}
