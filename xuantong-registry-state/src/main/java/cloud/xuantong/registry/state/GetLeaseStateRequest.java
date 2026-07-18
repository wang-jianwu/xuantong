package cloud.xuantong.registry.state;

public record GetLeaseStateRequest(InstanceKey instanceKey) {
    public GetLeaseStateRequest {
        if (instanceKey == null) {
            throw new IllegalArgumentException("instanceKey must not be null");
        }
    }
}
