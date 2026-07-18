package cloud.xuantong.gateway.socketd;

import java.util.concurrent.TimeUnit;

public record ControlPlaneRequestContext(
        String sessionId,
        String clientInstanceId,
        String applicationName,
        String principalId,
        String gatewayId,
        long connectionGeneration,
        String tenant,
        String namespaceId,
        String groupName,
        String remoteIp,
        long deadlineNanos) {

    public ControlPlaneRequestContext {
        sessionId = required("sessionId", sessionId);
        clientInstanceId = required("clientInstanceId", clientInstanceId);
        applicationName = required("applicationName", applicationName);
        principalId = required("principalId", principalId);
        gatewayId = required("gatewayId", gatewayId);
        tenant = required("tenant", tenant);
        namespaceId = required("namespaceId", namespaceId);
        groupName = required("groupName", groupName);
        remoteIp = remoteIp == null ? "" : remoteIp.trim();
        if (remoteIp.length() > 128) {
            throw new IllegalArgumentException("remoteIp must not exceed 128 characters");
        }
        if (connectionGeneration < 1) {
            throw new IllegalArgumentException("connectionGeneration must be positive");
        }
    }

    public long remainingBudgetNanos() {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    public long remainingBudgetMs() {
        long nanos = remainingBudgetNanos();
        if (nanos == 0) {
            return 0;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
