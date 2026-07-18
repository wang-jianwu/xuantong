package cloud.xuantong.config.state;

import java.util.List;

/** Rule submitted as part of one atomic release decision mutation. */
public record RolloutRuleDraft(
        String ruleId,
        long ruleGeneration,
        int selectorVersion,
        int priority,
        ConfigContentReference targetContent,
        String rolloutKey,
        RolloutSelectorType selectorType,
        String selectorKey,
        List<String> selectorValues,
        int percentageBasisPoints,
        long seed,
        RolloutRuleStatus status) {

    public RolloutRuleDraft {
        ruleId = required("ruleId", ruleId, 128);
        if (ruleGeneration < 1) {
            throw new IllegalArgumentException("ruleGeneration must be positive");
        }
        if (selectorVersion < 1) {
            throw new IllegalArgumentException("selectorVersion must be positive");
        }
        if (targetContent == null) {
            throw new IllegalArgumentException("targetContent must not be null");
        }
        rolloutKey = required("rolloutKey", rolloutKey, 256);
        if (selectorType == null) {
            throw new IllegalArgumentException("selectorType must not be null");
        }
        selectorKey = selectorKey == null ? "" : selectorKey.trim();
        selectorValues = normalizeValues(selectorValues);
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        validateSelector(selectorType, selectorKey, selectorValues, percentageBasisPoints);
    }

    private static List<String> normalizeValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> required("selectorValue", value, 512))
                .distinct()
                .sorted()
                .toList();
    }

    static void validateSelector(
            RolloutSelectorType type,
            String key,
            List<String> values,
            int percentageBasisPoints) {
        if (type == RolloutSelectorType.PERCENTAGE) {
            if (!key.isEmpty() || !values.isEmpty()) {
                throw new IllegalArgumentException(
                        "PERCENTAGE selector must not carry selectorKey or selectorValues");
            }
            if (percentageBasisPoints < 1 || percentageBasisPoints > 10_000) {
                throw new IllegalArgumentException(
                        "percentageBasisPoints must be between 1 and 10000");
            }
            return;
        }
        if (percentageBasisPoints != 0) {
            throw new IllegalArgumentException(
                    type + " selector must use percentageBasisPoints=0");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(type + " selector requires selectorValues");
        }
        if (type == RolloutSelectorType.TAG) {
            required("selectorKey", key, 128);
        } else if (!key.isEmpty()) {
            throw new IllegalArgumentException(type + " selector must not carry selectorKey");
        }
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
