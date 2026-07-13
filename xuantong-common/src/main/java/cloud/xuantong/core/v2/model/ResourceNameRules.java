package cloud.xuantong.core.v2.model;

import java.util.regex.Pattern;

public final class ResourceNameRules {
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private ResourceNameRules() {
    }

    public static String validate(String field, String value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String normalized = value.trim();
        if (!SEGMENT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters: " + value);
        }
        return normalized;
    }
}
