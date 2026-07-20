package cloud.xuantong.config.state;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.NavigableMap;

/** Pure deterministic selection shared by every applicable-release read path. */
public final class ConfigReleaseSelector {
    private ConfigReleaseSelector() {
    }

    public static ApplicableRelease select(
            ReleaseDecision decision,
            ConfigClientIdentity identity,
            NavigableMap<Long, ConfigContent> contents) {
        if (decision == null || identity == null || contents == null) {
            throw new IllegalArgumentException(
                    "decision, identity and contents must not be null");
        }
        if (!decision.active()) {
            throw new IllegalArgumentException("release selection requires an active decision");
        }
        for (RolloutRule rule : decision.rules()) {
            if (rule.status() == RolloutRuleStatus.ACTIVE && matches(rule, identity)) {
                return release(decision, contents, rule.targetContentRevision(), rule.ruleId());
            }
        }
        return release(decision, contents, decision.stableContentRevision(), "");
    }

    public static boolean matches(RolloutRule rule, ConfigClientIdentity identity) {
        return switch (rule.selectorType()) {
            case CLIENT_INSTANCE_ID -> rule.selectorValues().contains(identity.clientInstanceId());
            case APPLICATION_NAME -> rule.selectorValues().contains(identity.applicationName());
            case REMOTE_IP -> !identity.remoteIp().isEmpty()
                    && rule.selectorValues().contains(identity.remoteIp());
            case TAG -> rule.selectorValues().contains(
                    identity.trustedTags().get(rule.selectorKey()));
            case PERCENTAGE -> percentageBucket(rule, identity.clientInstanceId())
                    < rule.percentageBasisPoints();
        };
    }

    public static int percentageBucket(RolloutRule rule, String clientInstanceId) {
        String input = rule.rolloutKey() + "\n" + clientInstanceId + "\n" + rule.seed();
        byte[] digest = sha256(input.getBytes(StandardCharsets.UTF_8));
        long prefix = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        return (int) Long.remainderUnsigned(prefix, 10_000L);
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ApplicableRelease release(
            ReleaseDecision decision,
            NavigableMap<Long, ConfigContent> contents,
            long contentRevision,
            String ruleId) {
        ConfigContent content = contents.get(contentRevision);
        if (content == null) {
            throw new IllegalStateException(
                    "Decision references missing content revision " + contentRevision);
        }
        return new ApplicableRelease(
                ConfigValueState.ACTIVE,
                decision.configKey(),
                decision.decisionRevision(),
                content,
                ruleId);
    }
}
