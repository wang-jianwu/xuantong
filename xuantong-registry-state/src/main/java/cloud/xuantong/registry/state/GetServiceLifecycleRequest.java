package cloud.xuantong.registry.state;

public record GetServiceLifecycleRequest(ServiceKey serviceKey) {
    public GetServiceLifecycleRequest {
        if (serviceKey == null) {
            throw new IllegalArgumentException("serviceKey must not be null");
        }
    }
}
