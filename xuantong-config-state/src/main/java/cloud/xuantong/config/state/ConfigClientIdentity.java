package cloud.xuantong.config.state;

import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;

/** Identity already authenticated and enriched by the serving Gateway. */
public record ConfigClientIdentity(
        String clientInstanceId,
        String applicationName,
        String remoteIp,
        Map<String, String> trustedTags) {

    public ConfigClientIdentity {
        clientInstanceId = required("clientInstanceId", clientInstanceId, 256);
        applicationName = required("applicationName", applicationName, 256);
        remoteIp = remoteIp == null ? "" : remoteIp.trim();
        if (remoteIp.length() > 128) {
            throw new IllegalArgumentException("remoteIp must not exceed 128 characters");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        if (trustedTags != null) {
            trustedTags.forEach((key, value) -> normalized.put(
                    required("tag key", key, 128),
                    required("tag value", value, 512)));
        }
        trustedTags = Collections.unmodifiableMap(normalized);
    }

    private static String required(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        value = value.trim();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }
}
