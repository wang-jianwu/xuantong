package cloud.xuantong.gateway.socketd;

/** Trusted identity and exact session scope returned by the application authenticator. */
public record ControlPlanePrincipal(
        String principalId,
        String tenant,
        String namespaceId,
        String groupName,
        String credentialFingerprint,
        long expiresAtEpochMs,
        boolean anonymous) {

    public ControlPlanePrincipal {
        principalId = required("principalId", principalId, 256);
        tenant = required("tenant", tenant, 128);
        namespaceId = required("namespaceId", namespaceId, 128);
        groupName = required("groupName", groupName, 128);
        credentialFingerprint = credentialFingerprint == null
                ? "" : credentialFingerprint.trim();
        if (credentialFingerprint.length() > 128) {
            throw new IllegalArgumentException(
                    "credentialFingerprint must not exceed 128 characters");
        }
        if (expiresAtEpochMs < 0) {
            throw new IllegalArgumentException("expiresAtEpochMs must not be negative");
        }
    }

    public static ControlPlanePrincipal anonymous(
            String tenant, String namespaceId, String groupName) {
        return new ControlPlanePrincipal(
                "anonymous", tenant, namespaceId, groupName, "", 0L, true);
    }

    private static String required(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
