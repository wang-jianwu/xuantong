package cloud.xuantong.config.state;

/** Payload-free content identity used for SQL projection and restore validation. */
public record ConfigContentDigest(
        long contentRevision,
        String contentHash,
        String contentType,
        int schemaVersion,
        String blobReference) {

    public ConfigContentDigest {
        if (contentRevision < 1) {
            throw new IllegalArgumentException("contentRevision must be positive");
        }
        ConfigContentDraft.validateSha256(contentHash);
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        contentType = contentType.trim().toLowerCase(java.util.Locale.ROOT);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        blobReference = blobReference == null ? "" : blobReference.trim();
    }

    public static ConfigContentDigest from(ConfigContent content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return new ConfigContentDigest(
                content.contentRevision(),
                content.contentHash(),
                content.contentType(),
                content.schemaVersion(),
                content.blobReference());
    }
}
