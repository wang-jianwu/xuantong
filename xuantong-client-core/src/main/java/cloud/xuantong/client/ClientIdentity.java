package cloud.xuantong.client;

import java.lang.management.ManagementFactory;

/**
 * Stable identity for one application process connecting to Xuantong Brokers.
 * Multiple namespace/group subscriptions in the same JVM share the same clientId.
 */
public final class ClientIdentity {
    public static final String CLIENT_VERSION = "2.0.0-SNAPSHOT";
    private static final String PROCESS_ID = processId();

    private final String applicationName;
    private final String clientId;

    public ClientIdentity(String applicationName, String clientId) {
        this.applicationName = requireText("applicationName", applicationName, 128);
        String normalizedClientId = trimToNull(clientId);
        this.clientId = requireText("clientId",
                normalizedClientId == null ? this.applicationName + "@" + PROCESS_ID : normalizedClientId,
                256);
    }

    public static ClientIdentity defaultIdentity() {
        String applicationName = firstNonBlank(
                System.getProperty("xuantong.application.name"),
                System.getenv("XUANTONG_APPLICATION_NAME"),
                System.getProperty("spring.application.name"),
                System.getProperty("solon.app.name"),
                "xuantong-client");
        String clientId = firstNonBlank(
                System.getProperty("xuantong.client.id"),
                System.getenv("XUANTONG_CLIENT_ID"));
        return new ClientIdentity(applicationName, clientId);
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getClientId() {
        return clientId;
    }

    private static String processId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        return runtimeName == null || runtimeName.trim().isEmpty()
                ? Long.toHexString(System.currentTimeMillis())
                : runtimeName.trim();
    }

    private static String requireText(String field, String value, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) return normalized;
        }
        return null;
    }
}
