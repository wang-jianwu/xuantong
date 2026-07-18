package cloud.xuantong.config.state;

public record ResolveConfigOperationRequest(ConfigActor actor, String operationId) {
    public ResolveConfigOperationRequest {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        operationId = operationId.trim();
        if (operationId.length() > 256) {
            throw new IllegalArgumentException("operationId must not exceed 256 characters");
        }
    }
}
