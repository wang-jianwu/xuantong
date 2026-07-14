package cloud.xuantong.client;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/**
 * Stable identity for one application process connecting to Xuantong Brokers.
 * Multiple namespace/group subscriptions in the same JVM share the same
 * clientInstanceId.
 */
public final class ClientIdentity {
    public static final String CLIENT_VERSION = "2.0.0-SNAPSHOT";
    private static final String RUNTIME_INSTANCE_SUFFIX = runtimeInstanceSuffix();

    private final String applicationName;
    private final String clientInstanceId;

    public ClientIdentity(String applicationName, String clientInstanceId) {
        this.applicationName = requireText("applicationName", applicationName, 128);
        String configuredInstanceId = firstNonBlank(
                clientInstanceId,
                systemProperty("xuantong.client.instance-id"),
                environmentVariable("XUANTONG_CLIENT_INSTANCE_ID"));
        this.clientInstanceId = requireText("clientInstanceId",
                configuredInstanceId == null
                        ? this.applicationName + "@" + RUNTIME_INSTANCE_SUFFIX
                        : configuredInstanceId,
                256);
    }

    public static ClientIdentity defaultIdentity() {
        String applicationName = firstNonBlank(
                System.getProperty("xuantong.application.name"),
                System.getenv("XUANTONG_APPLICATION_NAME"),
                System.getProperty("spring.application.name"),
                System.getProperty("solon.app.name"),
                "xuantong-client");
        return new ClientIdentity(applicationName, null);
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getClientInstanceId() {
        return clientInstanceId;
    }

    private static String runtimeInstanceSuffix() {
        String podUid = environmentVariable("POD_UID");
        if (podUid != null) {
            return "pod-" + compact(podUid, 96);
        }

        String runtimeName = trimToNull(ManagementFactory.getRuntimeMXBean().getName());
        String processId = runtimeName == null ? "unknown" : runtimeName.split("@", 2)[0];
        String hostName = firstNonBlank(environmentVariable("HOSTNAME"), resolveHostName(), "unknown-host");
        String startedAt = Long.toHexString(ManagementFactory.getRuntimeMXBean().getStartTime());
        String bootId = UUID.randomUUID().toString().substring(0, 8);
        return compact(hostName, 64) + "-" + compact(processId, 32) + "-" + startedAt + "-" + bootId;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String systemProperty(String name) {
        try {
            return trimToNull(System.getProperty(name));
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String environmentVariable(String name) {
        try {
            return trimToNull(System.getenv(name));
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String compact(String value, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null) return "unknown";
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
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
