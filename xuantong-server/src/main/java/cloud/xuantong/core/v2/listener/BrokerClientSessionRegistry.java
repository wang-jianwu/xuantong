package cloud.xuantong.core.v2.listener;

import cloud.xuantong.core.event.ClientAccessTokenRevokedEvent;
import cloud.xuantong.core.service.ClientAccessTokenService;
import cloud.xuantong.core.v2.config.BrokerNodeConfig;
import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.transport.core.Session;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Init;
import org.noear.solon.core.event.EventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks authenticated Broker sessions without retaining raw access tokens.
 */
@Slf4j
@Component
public class BrokerClientSessionRegistry
        implements EventListener<ClientAccessTokenRevokedEvent> {
    @Inject
    private ClientAccessTokenService tokenService;
    @Inject
    private BrokerNodeConfig nodeConfig;
    @Inject("${broker.shutdownGraceMs:1000}")
    private long shutdownGraceMs;
    @Inject("${security.clientSessionRevalidateMs:5000}")
    private long revalidateIntervalMs;

    private final Map<String, TrackedSession> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService revalidateExecutor;

    @Init
    public void start() {
        if (revalidateIntervalMs <= 0L) {
            throw new IllegalArgumentException("security.clientSessionRevalidateMs must be positive");
        }
        revalidateExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-broker-session-authorizer");
            thread.setDaemon(true);
            return thread;
        });
        revalidateExecutor.scheduleWithFixedDelay(
                this::revalidateSafely,
                revalidateIntervalMs,
                revalidateIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    public boolean register(Channel channel, String rawToken, Session session) {
        if (session == null) {
            return false;
        }
        String clientId = normalized(session.param("clientId"), 256);
        String applicationName = normalized(session.param("applicationName"), 128);
        if (clientId == null || applicationName == null) {
            String missingFields = clientId == null && applicationName == null
                    ? "clientId,applicationName"
                    : clientId == null ? "clientId" : "applicationName";
            log.warn("Rejecting Broker session without 2.0 client identity: sessionId={}, channel={}, "
                            + "missing={}; upgrade to xuantong-client-core / xuantong-spring-boot-starter 2.0",
                    session.sessionId(), channel, missingFields);
            return false;
        }
        long now = System.currentTimeMillis();
        sessions.put(session.sessionId(), new TrackedSession(
                channel,
                tokenService.fingerprint(rawToken),
                clientId,
                applicationName,
                normalized(session.param("clientVersion"), 64),
                session.param("namespace"),
                session.param("group"),
                session.param("serviceName"),
                now,
                now,
                session));
        return true;
    }

    public void unregister(Session session) {
        if (session != null) {
            sessions.remove(session.sessionId());
        }
    }

    public void touch(Session session) {
        if (session == null) return;
        TrackedSession tracked = sessions.get(session.sessionId());
        if (tracked != null) tracked.lastActiveAt = System.currentTimeMillis();
    }

    public long logicalClientCount(Channel channel) {
        Set<String> clientIds = new HashSet<>();
        for (TrackedSession tracked : sessions.values()) {
            if (tracked.channel == channel && isActive(tracked.session)) {
                clientIds.add(tracked.clientId);
            }
        }
        return clientIds.size();
    }

    public long sessionCount(Channel channel) {
        long count = 0L;
        for (TrackedSession tracked : sessions.values()) {
            if (tracked.channel == channel && isActive(tracked.session)) count++;
        }
        return count;
    }

    public List<ClientConnectionView> connections() {
        List<ClientConnectionView> result = new ArrayList<>();
        String currentNodeId = nodeConfig == null ? "local" : nodeConfig.getNodeId();
        for (TrackedSession tracked : sessions.values()) {
            if (!isActive(tracked.session)) continue;
            result.add(new ClientConnectionView(
                    tracked.session.sessionId(), tracked.channel.name(), tracked.clientId,
                    tracked.applicationName, tracked.clientVersion, tracked.namespaceId,
                    tracked.groupName, tracked.serviceName, currentNodeId,
                    tracked.connectedAt, tracked.lastActiveAt));
        }
        result.sort(Comparator.comparingLong(ClientConnectionView::connectedAt).reversed());
        return result;
    }

    @Override
    public void onEvent(ClientAccessTokenRevokedEvent event) {
        if (event == null || event.tokenHash() == null || event.tokenHash().isBlank()) {
            return;
        }
        for (Map.Entry<String, TrackedSession> entry : sessions.entrySet()) {
            TrackedSession tracked = entry.getValue();
            if (!event.tokenHash().equals(tracked.tokenHash())) {
                continue;
            }
            if (sessions.remove(entry.getKey(), tracked)) {
                closeQuietly(tracked.session(), "revoked access token");
            }
        }
    }

    @Destroy
    public void stop() {
        if (revalidateExecutor != null) {
            revalidateExecutor.shutdownNow();
        }
        ArrayList<TrackedSession> snapshot = new ArrayList<>(sessions.values());
        for (TrackedSession tracked : snapshot) {
            try {
                if (tracked.session().isValid() && !tracked.session().isClosing()) {
                    tracked.session().preclose();
                }
            } catch (Exception e) {
                log.debug("Failed to preclose Broker session: sessionId={}",
                        tracked.session().sessionId(), e);
            }
        }
        if (!snapshot.isEmpty() && shutdownGraceMs > 0L) {
            try {
                Thread.sleep(Math.min(shutdownGraceMs, 30_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sessions.clear();
        for (TrackedSession tracked : snapshot) {
            closeQuietly(tracked.session(), "Broker shutdown");
        }
    }

    int size() {
        return sessions.size();
    }

    private void revalidateSafely() {
        try {
            Map<AuthorizationKey, Boolean> authorizationCache = new java.util.HashMap<>();
            for (Map.Entry<String, TrackedSession> entry : sessions.entrySet()) {
                TrackedSession tracked = entry.getValue();
                AuthorizationKey key = new AuthorizationKey(
                        tracked.tokenHash(), tracked.namespaceId(), tracked.groupName());
                boolean authorized = authorizationCache.computeIfAbsent(key,
                        ignored -> tokenService.isAuthorizedFingerprint(
                                key.tokenHash(), key.namespaceId(), key.groupName()));
                if (!authorized && sessions.remove(entry.getKey(), tracked)) {
                    closeQuietly(tracked.session(), "expired or revoked access token");
                }
            }
        } catch (Exception e) {
            log.warn("Broker client session authorization scan failed; it will retry", e);
        }
    }

    private void closeQuietly(Session session, String reason) {
        try {
            session.close();
            log.info("Broker session closed: sessionId={}, reason={}",
                    session.sessionId(), reason);
        } catch (Exception e) {
            log.debug("Failed to close Broker session: sessionId={}, reason={}",
                    session.sessionId(), reason, e);
        }
    }

    private boolean isActive(Session session) {
        try {
            return session != null && session.isActive() && !session.isClosing();
        } catch (Exception e) {
            return false;
        }
    }

    private String normalized(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) return null;
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) return null;
        }
        return normalized;
    }

    public enum Channel {
        CONFIG,
        DISCOVERY
    }

    public record ClientConnectionView(
            String sessionId,
            String channel,
            String clientId,
            String applicationName,
            String clientVersion,
            String namespaceId,
            String groupName,
            String serviceName,
            String nodeId,
            long connectedAt,
            long lastActiveAt) {
    }

    private static final class TrackedSession {
        private final Channel channel;
        private final String tokenHash;
        private final String clientId;
        private final String applicationName;
        private final String clientVersion;
        private final String namespaceId;
        private final String groupName;
        private final String serviceName;
        private final long connectedAt;
        private volatile long lastActiveAt;
        private final Session session;

        private TrackedSession(
                Channel channel,
                String tokenHash,
                String clientId,
                String applicationName,
                String clientVersion,
                String namespaceId,
                String groupName,
                String serviceName,
                long connectedAt,
                long lastActiveAt,
                Session session) {
            this.channel = channel;
            this.tokenHash = tokenHash;
            this.clientId = clientId;
            this.applicationName = applicationName;
            this.clientVersion = clientVersion;
            this.namespaceId = namespaceId;
            this.groupName = groupName;
            this.serviceName = serviceName;
            this.connectedAt = connectedAt;
            this.lastActiveAt = lastActiveAt;
            this.session = session;
        }

        private String tokenHash() { return tokenHash; }
        private String namespaceId() { return namespaceId; }
        private String groupName() { return groupName; }
        private Session session() { return session; }
    }

    private record AuthorizationKey(String tokenHash, String namespaceId, String groupName) {
    }
}
