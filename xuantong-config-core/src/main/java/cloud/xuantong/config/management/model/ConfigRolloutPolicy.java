package cloud.xuantong.config.management.model;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validated, canonical targeting policy for one immutable rollout candidate.
 */
public record ConfigRolloutPolicy(ReleaseType type, String targetValue) {
    private static final int BUCKETS = 10_000;

    public ConfigRolloutPolicy {
        if (type != ReleaseType.GRAY_IP && type != ReleaseType.GRAY_PERCENTAGE) {
            throw new IllegalArgumentException("Rollout type must be GRAY_IP or GRAY_PERCENTAGE");
        }
        if (targetValue == null || targetValue.isBlank()) {
            throw new IllegalArgumentException("Rollout target must not be empty");
        }
    }

    public static ConfigRolloutPolicy ip(Collection<String> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("GRAY_IP requires at least one target IP");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String target : targets) {
            normalized.add(normalizeIp(target));
        }
        if (normalized.size() > 1_000) {
            throw new IllegalArgumentException("GRAY_IP supports at most 1000 target IPs");
        }
        return new ConfigRolloutPolicy(ReleaseType.GRAY_IP, String.join(",", normalized));
    }

    public static ConfigRolloutPolicy percentage(Integer percentage) {
        if (percentage == null || percentage < 1 || percentage > 99) {
            throw new IllegalArgumentException("GRAY_PERCENTAGE must be between 1 and 99");
        }
        return new ConfigRolloutPolicy(ReleaseType.GRAY_PERCENTAGE, String.valueOf(percentage));
    }

    public static ConfigRolloutPolicy restore(ConfigRollout rollout) {
        if (rollout == null) {
            throw new IllegalArgumentException("Rollout must not be null");
        }
        ReleaseType type;
        try {
            type = ReleaseType.valueOf(rollout.getRolloutType());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid rollout type: " + rollout.getRolloutType(), e);
        }
        return new ConfigRolloutPolicy(type, rollout.getTargetValue());
    }

    public boolean matches(String rolloutId, String clientInstanceId, String clientIp) {
        return switch (type) {
            case GRAY_IP -> {
                String observedIp = normalizeObservedIp(clientIp);
                yield observedIp != null && ipTargets().contains(observedIp);
            }
            case GRAY_PERCENTAGE -> matchesPercentage(rolloutId, clientInstanceId);
            default -> false;
        };
    }

    public Set<String> ipTargets() {
        if (type != ReleaseType.GRAY_IP) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : targetValue.split(",")) {
            if (!value.isBlank()) result.add(value);
        }
        return Set.copyOf(result);
    }

    public int percentage() {
        if (type != ReleaseType.GRAY_PERCENTAGE) return 0;
        try {
            int percentage = Integer.parseInt(targetValue);
            if (percentage < 1 || percentage > 99) throw new NumberFormatException();
            return percentage;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid rollout percentage: " + targetValue, e);
        }
    }

    private boolean matchesPercentage(String rolloutId, String clientInstanceId) {
        if (rolloutId == null || rolloutId.isBlank()
                || clientInstanceId == null || clientInstanceId.isBlank()) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long seed = ByteBuffer.wrap(digest.digest(
                    rolloutId.getBytes(StandardCharsets.UTF_8))).getLong();
            byte[] hash = digest.digest((rolloutId + "\n" + clientInstanceId + "\n" + seed)
                    .getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(hash).getLong();
            long bucket = Long.remainderUnsigned(value, BUCKETS);
            return bucket < (long) percentage() * 100L;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate rollout bucket", e);
        }
    }

    private static String normalizeObservedIp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return normalizeIp(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeIp(String value) {
        if (value == null) throw new IllegalArgumentException("Target IP must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || !normalized.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("Invalid target IP: " + value);
        }
        try {
            return InetAddress.getByName(normalized).getHostAddress();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid target IP: " + value, e);
        }
    }
}
