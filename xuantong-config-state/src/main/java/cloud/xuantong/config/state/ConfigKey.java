package cloud.xuantong.config.state;

/** A configuration coordinate inside one Config State Group. */
public record ConfigKey(String namespace, String group, String dataId)
        implements Comparable<ConfigKey> {
    private static final int MAX_NAMESPACE_LENGTH = 128;
    private static final int MAX_GROUP_LENGTH = 128;
    private static final int MAX_DATA_ID_LENGTH = 256;

    public ConfigKey {
        namespace = required("namespace", namespace, MAX_NAMESPACE_LENGTH);
        group = required("group", group, MAX_GROUP_LENGTH);
        dataId = required("dataId", dataId, MAX_DATA_ID_LENGTH);
    }

    /**
     * Collision-free representation used as the CONFIG_DECISION revision scope.
     */
    public String canonicalName() {
        return component(namespace) + component(group) + component(dataId);
    }

    @Override
    public int compareTo(ConfigKey other) {
        return canonicalName().compareTo(other.canonicalName());
    }

    @Override
    public String toString() {
        return namespace + "/" + group + "/" + dataId;
    }

    private static String component(String value) {
        return value.length() + ":" + value;
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
