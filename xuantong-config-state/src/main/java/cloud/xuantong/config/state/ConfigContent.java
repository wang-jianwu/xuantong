package cloud.xuantong.config.state;

/** Immutable configuration payload or replicated blob reference. */
public record ConfigContent(
        ConfigKey configKey,
        long contentRevision,
        String contentHash,
        String contentType,
        int schemaVersion,
        byte[] payload,
        String blobReference) {

    public ConfigContent {
        if (configKey == null) {
            throw new IllegalArgumentException("configKey must not be null");
        }
        if (contentRevision < 1) {
            throw new IllegalArgumentException("contentRevision must be positive");
        }
        if (contentHash == null) {
            throw new IllegalArgumentException("contentHash must not be null");
        }
        contentHash = contentHash.trim().toLowerCase(java.util.Locale.ROOT);
        ConfigContentDraft.validateSha256(contentHash);
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        contentType = contentType.trim().toLowerCase(java.util.Locale.ROOT);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        payload = payload == null ? new byte[0] : payload.clone();
        blobReference = blobReference == null ? "" : blobReference.trim();
        if (!blobReference.isEmpty() && payload.length > 0) {
            throw new IllegalArgumentException(
                    "blob-backed content must not also carry an inline payload");
        }
    }

    public boolean inline() {
        return blobReference.isEmpty();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
