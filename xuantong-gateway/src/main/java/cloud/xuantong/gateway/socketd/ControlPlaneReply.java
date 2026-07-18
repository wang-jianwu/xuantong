package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import com.google.protobuf.ByteString;

public record ControlPlaneReply(
        ResponseStatus status,
        String payloadType,
        ByteString payload) {

    public ControlPlaneReply {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        payloadType = payloadType == null ? "" : payloadType.trim();
        payload = payload == null ? ByteString.EMPTY : payload;
        if (status.getCode() == ResponseCode.OK && payloadType.isEmpty()) {
            throw new IllegalArgumentException("Successful reply requires payloadType");
        }
    }

    public static ControlPlaneReply ok(String payloadType, ByteString payload) {
        return new ControlPlaneReply(
                ResponseStatus.newBuilder()
                        .setCode(ResponseCode.OK)
                        .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                        .build(),
                payloadType,
                payload);
    }

    public static ControlPlaneReply failure(ResponseStatus status) {
        return new ControlPlaneReply(status, "", ByteString.EMPTY);
    }
}
