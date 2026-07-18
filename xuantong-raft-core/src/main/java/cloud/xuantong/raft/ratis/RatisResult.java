package cloud.xuantong.raft.ratis;

public record RatisResult(byte[] payload, String serverId, long logIndex) {
    public RatisResult {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
