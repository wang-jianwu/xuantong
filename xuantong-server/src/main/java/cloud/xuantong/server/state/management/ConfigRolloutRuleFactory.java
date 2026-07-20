package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.RolloutRule;
import cloud.xuantong.config.state.RolloutRuleDraft;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.config.state.RolloutSelectorType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Builds the exact selector contract used by preview and authoritative Config State writes. */
final class ConfigRolloutRuleFactory {
    private ConfigRolloutRuleFactory() {
    }

    static RolloutRuleDraft activeCandidate(
            String ruleId, String rolloutKey, ConfigRolloutPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Rollout policy must not be null");
        }
        String normalizedRolloutKey = requireRolloutKey(rolloutKey);
        Selector selector = selector(policy);
        return new RolloutRuleDraft(
                ruleId,
                1,
                1,
                0,
                ConfigContentReference.newContent(),
                normalizedRolloutKey,
                selector.type(),
                "",
                selector.values(),
                selector.percentageBasisPoints(),
                stableSeed(normalizedRolloutKey),
                RolloutRuleStatus.ACTIVE);
    }

    static RolloutRule previewRule(String rolloutKey, ConfigRolloutPolicy policy) {
        RolloutRuleDraft draft = activeCandidate("preview", rolloutKey, policy);
        return new RolloutRule(
                draft.ruleId(),
                draft.ruleGeneration(),
                draft.selectorVersion(),
                draft.priority(),
                1,
                draft.rolloutKey(),
                draft.selectorType(),
                draft.selectorKey(),
                draft.selectorValues(),
                draft.percentageBasisPoints(),
                draft.seed(),
                draft.status(),
                1);
    }

    static String requireRolloutKey(String rolloutKey) {
        if (rolloutKey == null || rolloutKey.isBlank()) {
            throw new IllegalArgumentException(
                    "rolloutKey is required; preview the rollout before starting it");
        }
        String normalized = rolloutKey.trim();
        if (normalized.length() > 256
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid rolloutKey");
        }
        return normalized;
    }

    private static Selector selector(ConfigRolloutPolicy policy) {
        ReleaseType type = policy.type();
        return switch (type) {
            case GRAY_IP -> new Selector(
                    RolloutSelectorType.REMOTE_IP,
                    policy.ipTargets().stream().sorted().toList(),
                    0);
            case GRAY_CLIENT_INSTANCE -> new Selector(
                    RolloutSelectorType.CLIENT_INSTANCE_ID,
                    policy.clientInstanceTargets().stream().sorted().toList(),
                    0);
            case GRAY_PERCENTAGE -> new Selector(
                    RolloutSelectorType.PERCENTAGE,
                    List.of(),
                    policy.percentage() * 100);
            default -> throw new IllegalArgumentException(
                    "Unsupported rollout type: " + type);
        };
    }

    private static long stableSeed(String rolloutKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rolloutKey.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record Selector(
            RolloutSelectorType type,
            List<String> values,
            int percentageBasisPoints) {
    }
}
