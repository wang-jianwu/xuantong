package cloud.xuantong.registry.state;

public record RegistryMutationError(String code, String message) {
    public RegistryMutationError {
        code = InstanceKey.required("code", code, 128);
        message = message == null ? "" : message.trim();
        if (message.length() > 4096) {
            throw new IllegalArgumentException("message is too long");
        }
    }
}
