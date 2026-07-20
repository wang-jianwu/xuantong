package cloud.xuantong.config.management.model;

import java.net.InetAddress;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validated, canonical targeting policy for one immutable rollout candidate.
 */
public record ConfigRolloutPolicy(ReleaseType type, String targetValue) {
    public ConfigRolloutPolicy {
        if (type != ReleaseType.GRAY_IP
                && type != ReleaseType.GRAY_CLIENT_INSTANCE
                && type != ReleaseType.GRAY_PERCENTAGE) {
            throw new IllegalArgumentException(
                    "Rollout type must be GRAY_IP, GRAY_CLIENT_INSTANCE or GRAY_PERCENTAGE");
        }
        if (targetValue == null || targetValue.isBlank()) {
            throw new IllegalArgumentException("Rollout target must not be empty");
        }
        targetValue = switch (type) {
            case GRAY_IP -> canonicalIpTargets(targetValue);
            case GRAY_CLIENT_INSTANCE -> canonicalClientInstanceTargets(targetValue);
            case GRAY_PERCENTAGE -> canonicalPercentage(targetValue);
            default -> throw new IllegalArgumentException("Unsupported rollout type: " + type);
        };
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

    public static ConfigRolloutPolicy clientInstances(Collection<String> clientInstanceIds) {
        if (clientInstanceIds == null || clientInstanceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "GRAY_CLIENT_INSTANCE requires at least one clientInstanceId");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String clientInstanceId : clientInstanceIds) {
            if (clientInstanceId == null || clientInstanceId.isBlank()) {
                throw new IllegalArgumentException("clientInstanceId must not be blank");
            }
            String value = clientInstanceId.trim();
            if (value.length() > 256 || value.indexOf(',') >= 0
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid clientInstanceId: " + clientInstanceId);
            }
            normalized.add(value);
        }
        if (normalized.size() > 1_000) {
            throw new IllegalArgumentException(
                    "GRAY_CLIENT_INSTANCE supports at most 1000 client instances");
        }
        return new ConfigRolloutPolicy(
                ReleaseType.GRAY_CLIENT_INSTANCE, String.join(",", normalized));
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

    public Set<String> clientInstanceTargets() {
        if (type != ReleaseType.GRAY_CLIENT_INSTANCE) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : targetValue.split(",")) {
            if (!value.isBlank()) result.add(value);
        }
        return Set.copyOf(result);
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

    private static String canonicalIpTargets(String value) {
        TreeSet<String> targets = new TreeSet<>();
        for (String target : value.split(",", -1)) {
            targets.add(normalizeIp(target));
        }
        if (targets.size() > 1_000) {
            throw new IllegalArgumentException("GRAY_IP supports at most 1000 target IPs");
        }
        return String.join(",", targets);
    }

    private static String canonicalClientInstanceTargets(String value) {
        TreeSet<String> targets = new TreeSet<>();
        for (String target : value.split(",", -1)) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("clientInstanceId must not be blank");
            }
            String normalized = target.trim();
            if (normalized.length() > 256
                    || normalized.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid clientInstanceId: " + target);
            }
            targets.add(normalized);
        }
        if (targets.size() > 1_000) {
            throw new IllegalArgumentException(
                    "GRAY_CLIENT_INSTANCE supports at most 1000 client instances");
        }
        return String.join(",", targets);
    }

    private static String canonicalPercentage(String value) {
        try {
            int percentage = Integer.parseInt(value.trim());
            if (percentage < 1 || percentage > 99) {
                throw new NumberFormatException();
            }
            return Integer.toString(percentage);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "GRAY_PERCENTAGE must be between 1 and 99", e);
        }
    }
}
