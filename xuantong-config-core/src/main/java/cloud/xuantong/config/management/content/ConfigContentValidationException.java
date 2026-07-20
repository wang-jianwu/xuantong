package cloud.xuantong.config.management.content;

public class ConfigContentValidationException extends IllegalArgumentException {
    private final ConfigContentResult result;

    public ConfigContentValidationException(ConfigContentResult result) {
        super(result == null || result.issues().isEmpty()
                ? "Invalid config content"
                : result.issues().getFirst().message());
        this.result = result;
    }

    public ConfigContentResult result() {
        return result;
    }
}
