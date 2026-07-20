package cloud.xuantong.client;

/**
 * Immutable TLS/mTLS settings shared by the Java client and every framework adapter.
 * Store paths are read again when their content changes so certificate rotation can
 * rebuild the control-plane connection without restarting the application.
 */
public record TlsOptions(
        boolean enabled,
        String trustStore,
        String trustStoreType,
        String trustStorePassword,
        String keyStore,
        String keyStoreType,
        String keyStorePassword,
        String keyPassword,
        boolean hostnameVerification,
        long reloadIntervalMs) {

    public TlsOptions {
        trustStore = normalizePath(trustStore);
        trustStoreType = normalizeType(trustStoreType);
        trustStorePassword = normalizeSecret(trustStorePassword);
        keyStore = normalizePath(keyStore);
        keyStoreType = normalizeType(keyStoreType);
        keyStorePassword = normalizeSecret(keyStorePassword);
        keyPassword = keyPassword == null || keyPassword.isEmpty()
                ? keyStorePassword : keyPassword;
        if (!enabled && (!trustStore.isEmpty() || !keyStore.isEmpty())) {
            throw new IllegalArgumentException(
                    "TLS store paths require enabled=true");
        }
        if (reloadIntervalMs < 1_000L || reloadIntervalMs > 3_600_000L) {
            throw new IllegalArgumentException(
                    "reloadIntervalMs must be between 1000 and 3600000");
        }
    }

    public static TlsOptions disabled() {
        return new TlsOptions(
                false, "", "PKCS12", "", "", "PKCS12", "", "",
                true, 30_000L);
    }

    public static TlsOptions enabled(
            String trustStore,
            String trustStoreType,
            String trustStorePassword,
            String keyStore,
            String keyStoreType,
            String keyStorePassword,
            String keyPassword,
            boolean hostnameVerification,
            long reloadIntervalMs) {
        return new TlsOptions(
                true,
                trustStore,
                trustStoreType,
                trustStorePassword,
                keyStore,
                keyStoreType,
                keyStorePassword,
                keyPassword,
                hostnameVerification,
                reloadIntervalMs);
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank() ? "PKCS12" : value.trim();
    }

    private static String normalizeSecret(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return "TlsOptions[enabled=" + enabled
                + ", trustStore=" + trustStore
                + ", trustStoreType=" + trustStoreType
                + ", trustStorePassword=<redacted>"
                + ", keyStore=" + keyStore
                + ", keyStoreType=" + keyStoreType
                + ", keyStorePassword=<redacted>"
                + ", keyPassword=<redacted>"
                + ", hostnameVerification=" + hostnameVerification
                + ", reloadIntervalMs=" + reloadIntervalMs + ']';
    }
}
