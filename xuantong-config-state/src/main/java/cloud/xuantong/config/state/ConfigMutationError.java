package cloud.xuantong.config.state;

public record ConfigMutationError(String code, String message) {
    public ConfigMutationError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        code = code.trim();
        message = message == null ? "" : message.trim();
    }
}
