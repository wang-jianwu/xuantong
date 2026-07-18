package cloud.xuantong.registry.state;

public record ResolveRegistryOperationRequest(
        RegistryActor actor,
        String operationId) {

    public ResolveRegistryOperationRequest {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        operationId = InstanceKey.required("operationId", operationId, 256);
    }
}
