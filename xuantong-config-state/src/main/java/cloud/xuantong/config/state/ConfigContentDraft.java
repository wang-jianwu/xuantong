package cloud.xuantong.config.state;

import java.util.HexFormat;

/** New immutable content carried by a decision mutation. */
public record ConfigContentDraft(
        String contentType,
        int schemaVersion,
        byte[] payload,
        String contentHash,
        String blobReference) {

    public ConfigContentDraft {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        contentType = contentType.trim().toLowerCase(java.util.Locale.ROOT);
        if (contentType.length() > 64) {
            throw new IllegalArgumentException("contentType must not exceed 64 characters");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        payload = payload == null ? new byte[0] : payload.clone();
        contentHash = contentHash == null ? "" : contentHash.trim().toLowerCase(java.util.Locale.ROOT);
        blobReference = blobReference == null ? "" : blobReference.trim();
        if (!blobReference.isEmpty() && payload.length > 0) {
            throw new IllegalArgumentException(
                    "blob-backed content must not also carry an inline payload");
        }
        if (!contentHash.isEmpty()) {
            validateSha256(contentHash);
        }
        if (!blobReference.isEmpty() && contentHash.isEmpty()) {
            throw new IllegalArgumentException("blob-backed content requires contentHash");
        }
        if (blobReference.length() > 2048) {
            throw new IllegalArgumentException("blobReference must not exceed 2048 characters");
        }
    }

    public static ConfigContentDraft inline(
            String contentType, int schemaVersion, byte[] payload) {
        return new ConfigContentDraft(contentType, schemaVersion, payload, "", "");
    }

    public static ConfigContentDraft blob(
            String contentType, int schemaVersion, String contentHash, String blobReference) {
        return new ConfigContentDraft(
                contentType, schemaVersion, new byte[0], contentHash, blobReference);
    }

    public boolean inline() {
        return blobReference.isEmpty();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    static void validateSha256(String hash) {
        if (hash.length() != 64) {
            throw new IllegalArgumentException("contentHash must be a SHA-256 hex value");
        }
        try {
            HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contentHash must be a SHA-256 hex value", e);
        }
    }
}
