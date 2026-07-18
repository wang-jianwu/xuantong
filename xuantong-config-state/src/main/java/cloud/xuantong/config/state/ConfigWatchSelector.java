package cloud.xuantong.config.state;

import java.util.List;

/** Scope-safe selector for Config ChangeLog watches. */
public record ConfigWatchSelector(
        String namespace,
        String group,
        List<ConfigKey> configKeys) {
    public ConfigWatchSelector {
        namespace = required("namespace", namespace);
        group = required("group", group);
        configKeys = configKeys == null ? List.of() : List.copyOf(configKeys);
        for (ConfigKey key : configKeys) {
            if (!namespace.equals(key.namespace()) || !group.equals(key.group())) {
                throw new IllegalArgumentException(
                        "Config Watch key is outside the selector scope: " + key);
            }
        }
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
