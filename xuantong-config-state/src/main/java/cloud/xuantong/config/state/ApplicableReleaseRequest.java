package cloud.xuantong.config.state;

public record ApplicableReleaseRequest(
        ConfigKey configKey,
        ConfigClientIdentity identity) {
    public ApplicableReleaseRequest {
        if (configKey == null) {
            throw new IllegalArgumentException("configKey must not be null");
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
    }
}
