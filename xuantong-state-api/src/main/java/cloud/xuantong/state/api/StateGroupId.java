package cloud.xuantong.state.api;

import java.util.Locale;

public record StateGroupId(StateGroupType type, String value) {
    private static final int MAX_VALUE_LENGTH = 128;

    public StateGroupId {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        value = value.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("value must not exceed "
                    + MAX_VALUE_LENGTH + " characters");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("value must not contain whitespace");
        }
    }

    public static StateGroupId config(String value) {
        return new StateGroupId(StateGroupType.CONFIG, value);
    }

    public static StateGroupId registry(String value) {
        return new StateGroupId(StateGroupType.REGISTRY, value);
    }

    public static StateGroupId meta(String value) {
        return new StateGroupId(StateGroupType.META, value);
    }

    public String canonicalName() {
        return type.name().toLowerCase(Locale.ROOT) + ":" + value;
    }

    @Override
    public String toString() {
        return canonicalName();
    }
}
