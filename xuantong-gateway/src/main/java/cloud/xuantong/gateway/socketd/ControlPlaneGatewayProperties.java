package cloud.xuantong.gateway.socketd;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.util.Locale;
import java.util.UUID;

@Configuration
public class ControlPlaneGatewayProperties {
    private final String generatedGatewayId = "gateway-" + UUID.randomUUID().toString().substring(0, 8);

    @Inject("${controlPlane.host:0.0.0.0}")
    private String host;
    @Inject("${controlPlane.port:8090}")
    private int port;
    @Inject("${controlPlane.clusterId:xuantong-local}")
    private String clusterId;
    @Inject("${controlPlane.gatewayId:}")
    private String configuredGatewayId;
    @Inject("${controlPlane.transportGeneration:1}")
    private long transportGeneration;
    @Inject("${controlPlane.maxRequestBudgetMs:10000}")
    private long maxRequestBudgetMs;
    @Inject("${controlPlane.maxRequestBytes:1048576}")
    private int maxRequestBytes;
    @Inject("${controlPlane.maxInFlightRequests:1024}")
    private int maxInFlightRequests;
    @Inject("${controlPlane.maxSessions:20000}")
    private int maxSessions;
    @Inject("${controlPlane.maxSessionsPerTenant:10000}")
    private int maxSessionsPerTenant;
    @Inject("${controlPlane.maxSessionsPerCredential:1000}")
    private int maxSessionsPerCredential;
    @Inject("${controlPlane.maxSubscriptions:10000}")
    private int maxSubscriptions;
    @Inject("${controlPlane.maxSubscriptionsPerTenant:2000}")
    private int maxSubscriptionsPerTenant;
    @Inject("${controlPlane.tenantRequestRatePerSecond:2000}")
    private int tenantRequestRatePerSecond;
    @Inject("${controlPlane.tenantRequestBurst:4000}")
    private int tenantRequestBurst;
    @Inject("${controlPlane.authFailureLimit:20}")
    private int authFailureLimit;
    @Inject("${controlPlane.authFailureWindowMs:60000}")
    private long authFailureWindowMs;
    @Inject("${controlPlane.watchPollIntervalMs:250}")
    private long watchPollIntervalMs;
    @Inject("${controlPlane.watchIdlePollMaxIntervalMs:5000}")
    private long watchIdlePollMaxIntervalMs;
    @Inject("${controlPlane.watchAckTimeoutMs:15000}")
    private long watchAckTimeoutMs;
    @Inject("${controlPlane.watchStreamMaxLifetimeMs:900000}")
    private long watchStreamMaxLifetimeMs;
    @Inject("${controlPlane.overloadRetryAfterMs:100}")
    private long overloadRetryAfterMs;
    @Inject("${controlPlane.workThreads:0}")
    private int workThreads;
    @Inject("${controlPlane.workQueueCapacity:4096}")
    private int workQueueCapacity;
    @Inject("${controlPlane.stateCallbackThreads:0}")
    private int stateCallbackThreads;
    @Inject("${controlPlane.stateCallbackQueueCapacity:1024}")
    private int stateCallbackQueueCapacity;
    @Inject("${controlPlane.idleTimeoutMs:60000}")
    private int idleTimeoutMs;
    @Inject("${controlPlane.drainTimeoutMs:5000}")
    private long drainTimeoutMs;
    @Inject("${controlPlane.authRevalidateIntervalMs:5000}")
    private long authRevalidateIntervalMs;
    @Inject("${controlPlane.helloTimeoutMs:5000}")
    private long helloTimeoutMs;
    @Inject("${controlPlane.tls.clientAuth:NONE}")
    private String tlsClientAuth;
    @Inject("${security.clientAuthRequired:false}")
    private boolean applicationAuthRequired;
    @Inject("${security.production:false}")
    private boolean production;

    public ControlPlaneGatewayProperties() {
    }

    ControlPlaneGatewayProperties(String clusterId, String gatewayId,
                                  long transportGeneration, long maxRequestBudgetMs) {
        this.host = "127.0.0.1";
        this.port = 0;
        this.clusterId = clusterId;
        this.configuredGatewayId = gatewayId;
        this.transportGeneration = transportGeneration;
        this.maxRequestBudgetMs = maxRequestBudgetMs;
        this.maxRequestBytes = 1024 * 1024;
        this.maxInFlightRequests = 1024;
        this.maxSessions = 20_000;
        this.maxSessionsPerTenant = 10_000;
        this.maxSessionsPerCredential = 1_000;
        this.maxSubscriptions = 10_000;
        this.maxSubscriptionsPerTenant = 2_000;
        this.tenantRequestRatePerSecond = 2_000;
        this.tenantRequestBurst = 4_000;
        this.authFailureLimit = 20;
        this.authFailureWindowMs = 60_000L;
        this.watchPollIntervalMs = 250L;
        this.watchIdlePollMaxIntervalMs = 5_000L;
        this.watchAckTimeoutMs = 15_000L;
        this.watchStreamMaxLifetimeMs = 900_000L;
        this.overloadRetryAfterMs = 100L;
        this.workQueueCapacity = 4096;
        this.stateCallbackQueueCapacity = 1024;
        this.idleTimeoutMs = 60_000;
        this.drainTimeoutMs = 5_000L;
        this.authRevalidateIntervalMs = 5_000L;
        this.helloTimeoutMs = 5_000L;
        this.tlsClientAuth = ClientAuth.NONE.name();
    }

    ControlPlaneGatewayProperties(
            String host,
            int port,
            String clusterId,
            String gatewayId,
            long transportGeneration,
            long maxRequestBudgetMs,
            int maxInFlightRequests,
            int workThreads,
            int workQueueCapacity,
            long drainTimeoutMs,
            ClientAuth clientAuth) {
        this(clusterId, gatewayId, transportGeneration, maxRequestBudgetMs);
        this.host = host;
        this.port = port;
        this.maxInFlightRequests = maxInFlightRequests;
        this.workThreads = workThreads;
        this.workQueueCapacity = workQueueCapacity;
        this.drainTimeoutMs = drainTimeoutMs;
        this.tlsClientAuth = clientAuth.name();
    }

    ControlPlaneGatewayProperties(
            String clusterId,
            String gatewayId,
            long transportGeneration,
            long maxRequestBudgetMs,
            boolean applicationAuthRequired,
            long authRevalidateIntervalMs) {
        this(clusterId, gatewayId, transportGeneration, maxRequestBudgetMs,
                applicationAuthRequired, authRevalidateIntervalMs, false);
    }

    ControlPlaneGatewayProperties(
            String clusterId,
            String gatewayId,
            long transportGeneration,
            long maxRequestBudgetMs,
            boolean applicationAuthRequired,
            long authRevalidateIntervalMs,
            boolean production) {
        this(clusterId, gatewayId, transportGeneration, maxRequestBudgetMs);
        this.applicationAuthRequired = applicationAuthRequired;
        this.authRevalidateIntervalMs = authRevalidateIntervalMs;
        this.production = production;
    }

    public String getHost() {
        return host == null || host.isBlank() ? "0.0.0.0" : host.trim();
    }

    public int getPort() {
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("controlPlane.port must be between 1 and 65535");
        }
        return port;
    }

    public String getClusterId() {
        return clusterId == null || clusterId.isBlank() ? "xuantong-local" : clusterId.trim();
    }

    public String getGatewayId() {
        return configuredGatewayId == null || configuredGatewayId.isBlank()
                ? generatedGatewayId
                : configuredGatewayId.trim();
    }

    public long getTransportGeneration() {
        return Math.max(1, transportGeneration);
    }

    public long getMaxRequestBudgetMs() {
        return maxRequestBudgetMs > 0 ? maxRequestBudgetMs : 10_000L;
    }

    public int getMaxRequestBytes() {
        if (maxRequestBytes < 1024 || maxRequestBytes > 16 * 1024 * 1024) {
            throw new IllegalStateException(
                    "controlPlane.maxRequestBytes must be between 1024 and 16777216");
        }
        return maxRequestBytes;
    }

    public int getMaxInFlightRequests() {
        if (maxInFlightRequests < 1) {
            throw new IllegalStateException("controlPlane.maxInFlightRequests must be positive");
        }
        return maxInFlightRequests;
    }

    public int getMaxSessions() {
        if (maxSessions < 1) {
            throw new IllegalStateException("controlPlane.maxSessions must be positive");
        }
        return maxSessions;
    }

    public int getMaxSessionsPerTenant() {
        if (maxSessionsPerTenant < 1) {
            throw new IllegalStateException(
                    "controlPlane.maxSessionsPerTenant must be positive");
        }
        return maxSessionsPerTenant;
    }

    public int getMaxSessionsPerCredential() {
        if (maxSessionsPerCredential < 1) {
            throw new IllegalStateException(
                    "controlPlane.maxSessionsPerCredential must be positive");
        }
        return maxSessionsPerCredential;
    }

    public int getMaxSubscriptions() {
        if (maxSubscriptions < 1) {
            throw new IllegalStateException("controlPlane.maxSubscriptions must be positive");
        }
        return maxSubscriptions;
    }

    public int getMaxSubscriptionsPerTenant() {
        if (maxSubscriptionsPerTenant < 1) {
            throw new IllegalStateException(
                    "controlPlane.maxSubscriptionsPerTenant must be positive");
        }
        return maxSubscriptionsPerTenant;
    }

    public int getTenantRequestRatePerSecond() {
        if (tenantRequestRatePerSecond < 1) {
            throw new IllegalStateException(
                    "controlPlane.tenantRequestRatePerSecond must be positive");
        }
        return tenantRequestRatePerSecond;
    }

    public int getTenantRequestBurst() {
        if (tenantRequestBurst < 1) {
            throw new IllegalStateException(
                    "controlPlane.tenantRequestBurst must be positive");
        }
        return tenantRequestBurst;
    }

    public int getAuthFailureLimit() {
        if (authFailureLimit < 1) {
            throw new IllegalStateException(
                    "controlPlane.authFailureLimit must be positive");
        }
        return authFailureLimit;
    }

    public long getAuthFailureWindowMs() {
        if (authFailureWindowMs < 1_000L || authFailureWindowMs > 3_600_000L) {
            throw new IllegalStateException(
                    "controlPlane.authFailureWindowMs must be between 1000 and 3600000");
        }
        return authFailureWindowMs;
    }

    public long getWatchPollIntervalMs() {
        if (watchPollIntervalMs < 10L || watchPollIntervalMs > 60_000L) {
            throw new IllegalStateException(
                    "controlPlane.watchPollIntervalMs must be between 10 and 60000");
        }
        return watchPollIntervalMs;
    }

    public long getWatchIdlePollMaxIntervalMs() {
        long minimum = getWatchPollIntervalMs();
        if (watchIdlePollMaxIntervalMs < minimum
                || watchIdlePollMaxIntervalMs > 300_000L) {
            throw new IllegalStateException(
                    "controlPlane.watchIdlePollMaxIntervalMs must be between "
                            + minimum + " and 300000");
        }
        return watchIdlePollMaxIntervalMs;
    }

    public long getWatchAckTimeoutMs() {
        if (watchAckTimeoutMs < 1_000L || watchAckTimeoutMs > 300_000L) {
            throw new IllegalStateException(
                    "controlPlane.watchAckTimeoutMs must be between 1000 and 300000");
        }
        return watchAckTimeoutMs;
    }

    public long getWatchStreamMaxLifetimeMs() {
        if (watchStreamMaxLifetimeMs < 10_000L
                || watchStreamMaxLifetimeMs > 3_600_000L) {
            throw new IllegalStateException(
                    "controlPlane.watchStreamMaxLifetimeMs must be between 10000 and 3600000");
        }
        if (watchStreamMaxLifetimeMs <= getWatchAckTimeoutMs()) {
            throw new IllegalStateException(
                    "controlPlane.watchStreamMaxLifetimeMs must exceed watchAckTimeoutMs");
        }
        return watchStreamMaxLifetimeMs;
    }

    public long getOverloadRetryAfterMs() {
        return overloadRetryAfterMs > 0 ? overloadRetryAfterMs : 100L;
    }

    public int getWorkThreads() {
        if (workThreads < 0) {
            throw new IllegalStateException("controlPlane.workThreads must not be negative");
        }
        if (workThreads > 0) {
            return workThreads;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(4, Math.min(32, processors * 2));
    }

    public int getWorkQueueCapacity() {
        if (workQueueCapacity < 1) {
            throw new IllegalStateException("controlPlane.workQueueCapacity must be positive");
        }
        return workQueueCapacity;
    }

    public int getStateCallbackThreads() {
        if (stateCallbackThreads < 0) {
            throw new IllegalStateException(
                    "controlPlane.stateCallbackThreads must not be negative");
        }
        if (stateCallbackThreads > 0) {
            return stateCallbackThreads;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(16, processors));
    }

    public int getStateCallbackQueueCapacity() {
        if (stateCallbackQueueCapacity < 1) {
            throw new IllegalStateException(
                    "controlPlane.stateCallbackQueueCapacity must be positive");
        }
        return stateCallbackQueueCapacity;
    }

    public int getIdleTimeoutMs() {
        if (idleTimeoutMs < 1_000) {
            throw new IllegalStateException("controlPlane.idleTimeoutMs must be at least 1000");
        }
        return idleTimeoutMs;
    }

    public long getDrainTimeoutMs() {
        if (drainTimeoutMs < 0) {
            throw new IllegalStateException("controlPlane.drainTimeoutMs must not be negative");
        }
        return drainTimeoutMs;
    }

    public long getAuthRevalidateIntervalMs() {
        if (authRevalidateIntervalMs < 100L || authRevalidateIntervalMs > 300_000L) {
            throw new IllegalStateException(
                    "controlPlane.authRevalidateIntervalMs must be between 100 and 300000");
        }
        return authRevalidateIntervalMs;
    }

    public long getHelloTimeoutMs() {
        if (helloTimeoutMs < 100L || helloTimeoutMs > 60_000L) {
            throw new IllegalStateException(
                    "controlPlane.helloTimeoutMs must be between 100 and 60000");
        }
        return helloTimeoutMs;
    }

    public boolean isApplicationAuthRequired() {
        return applicationAuthRequired;
    }

    public boolean isProduction() {
        return production;
    }

    public ClientAuth getTlsClientAuth() {
        try {
            return ClientAuth.valueOf((tlsClientAuth == null ? "NONE" : tlsClientAuth.trim())
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "controlPlane.tls.clientAuth must be NONE, WANT, or REQUIRE", e);
        }
    }

    public enum ClientAuth {
        NONE,
        WANT,
        REQUIRE
    }
}
