package cloud.xuantong.config.management.content;

import java.util.Locale;

public enum ConfigContentType {
    TEXT,
    STRING,
    NUMBER,
    BOOLEAN,
    PROPERTIES,
    YAML,
    JSON,
    XML;

    public static ConfigContentType parse(String value) {
        String normalized = value == null || value.isBlank()
                ? TEXT.name()
                : value.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported contentType: " + value);
        }
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
