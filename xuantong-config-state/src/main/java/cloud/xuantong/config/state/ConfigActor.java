package cloud.xuantong.config.state;

/** Authenticated idempotency scope injected by the Gateway. */
public record ConfigActor(String tenant, String principal) {
    public ConfigActor {
        tenant = required("tenant", tenant);
        principal = required("principal", principal);
    }

    public String idempotencyScope() {
        return tenant.length() + ":" + tenant + principal.length() + ":" + principal;
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        value = value.trim();
        if (value.length() > 256) {
            throw new IllegalArgumentException(field + " must not exceed 256 characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }
}
