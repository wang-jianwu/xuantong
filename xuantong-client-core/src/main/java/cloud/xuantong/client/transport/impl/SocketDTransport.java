package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.ConfigTransport;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Socket.D multi-broker configuration transport.
 *
 * Every Broker owns an independent auto-reconnecting Socket.D session. Reads are
 * raced across all active Brokers so a slow or broken node cannot delay a healthy one.
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);
    private static final long HEARTBEAT_INTERVAL = 20_000L;
    private static final long CONNECTION_TIMEOUT = 5_000L;
    private static final long REQUEST_TIMEOUT = 5_000L;
    private static final long OPERATION_TIMEOUT = REQUEST_TIMEOUT + 1_000L;

    private final ClientIdentity identity;

    private final Map<Integer, ClientSession> sessions =
            new ConcurrentHashMap<>();
    private volatile List<String> brokerUrls = Collections.emptyList();
    private volatile String subscriberName = "";
    private volatile String accessToken = "";
    private volatile ConfigChangeListener changeListener;
    private volatile Runnable reconnectListener;
    private volatile boolean closed;
    private ExecutorService operationExecutor;
    private ExecutorService callbackExecutor;

    public SocketDTransport() {
        this(ClientIdentity.defaultIdentity());
    }

    public SocketDTransport(ClientIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        this.identity = identity;
    }

    @Override
    public void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            ConfigChangeListener listener) {
        if (serverAddresses == null || serverAddresses.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be empty");
        }
        this.changeListener = listener;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.subscriberName = "config:" + namespace + ":" + group;
        this.brokerUrls = buildBrokerUrls(serverAddresses, namespace, group);
        this.operationExecutor = newDaemonPool(
                Math.min(16, Math.max(2, brokerUrls.size())),
                "xuantong-config-broker-operation");
        this.callbackExecutor = newDaemonPool(1, "xuantong-config-broker-callback");
        openAllSessions();
        if (availableSessions().isEmpty()) {
            logger.warn("All config-v2 Brokers are unavailable; Socket.D will reconnect in background");
        }
    }

    @Override
    public ConfigSnapshot fetch(final String dataId) {
        List<Map.Entry<Integer, ClientSession>> available = availableSessions();
        if (available.isEmpty()) {
            return null;
        }
        CompletionService<FetchResult> completion =
                new ExecutorCompletionService<FetchResult>(operationExecutor);
        List<Future<FetchResult>> futures = new ArrayList<Future<FetchResult>>();
        for (final Map.Entry<Integer, ClientSession> entry : available) {
            futures.add(completion.submit(new Callable<FetchResult>() {
                @Override
                public FetchResult call() {
                    try {
                        return FetchResult.success(entry.getKey(),
                                fetchFromSession(entry.getValue(), dataId));
                    } catch (Exception e) {
                        return FetchResult.failure(entry.getKey(), e);
                    }
                }
            }));
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OPERATION_TIMEOUT);
        try {
            for (int completed = 0; completed < futures.size(); completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                Future<FetchResult> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                FetchResult result = future.get();
                if (result.error == null) {
                    cancelAll(futures);
                    return result.snapshot;
                }
                logger.warn("Config fetch failed via Broker {}: dataId={}, error={}",
                        result.brokerIndex, dataId, result.error.toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Config fetch failed: dataId={}", dataId, e);
        } finally {
            cancelAll(futures);
        }
        return null;
    }

    protected ConfigSnapshot fetchFromSession(ClientSession session, String dataId) throws Exception {
        Entity response = SocketDRpcSupport.request(session, "/get",
                new StringEntity("{}").at(subscriberName).metaPut("dataId", dataId),
                REQUEST_TIMEOUT);
        if ("false".equals(response.meta("found"))) {
            return null;
        }
        return new ConfigSnapshot(
                dataId,
                response.dataAsString(),
                parseRevision(response.meta("revision")),
                response.meta("checksum"),
                response.meta("contentType"));
    }

    protected ClientSession openSession(final int brokerIndex, String url) {
        return SocketD.createClient(url)
                .config(c -> {
                    c.heartbeatInterval(HEARTBEAT_INTERVAL)
                            .connectTimeout(CONNECTION_TIMEOUT)
                            .requestTimeout(REQUEST_TIMEOUT)
                            .autoReconnect(true);
                    if (!accessToken.isEmpty()) {
                        c.metaPut("token", accessToken);
                    }
                })
                .listen(new EventListener()
                        .doOn("/config-change", (session, message) -> {
                            ConfigChangeListener listener = changeListener;
                            if (listener != null) {
                                listener.onChanged(message.dataAsString());
                            }
                        })
                        .doOnOpen(session -> handleOpened(brokerIndex, session))
                        .doOnClose(session -> handleClosed(brokerIndex, session))
                        .doOnError((session, error) -> logger.warn(
                                "config-v2 Broker {} connection error", brokerIndex, error)))
                .open();
    }

    int activeSessionCount() {
        return availableSessions().size();
    }

    private void openAllSessions() {
        ExecutorService connectorExecutor = newDaemonPool(
                Math.max(1, brokerUrls.size()), "xuantong-config-broker-connect");
        List<Callable<OpenedSession>> tasks = new ArrayList<Callable<OpenedSession>>();
        for (int index = 0; index < brokerUrls.size(); index++) {
            final int brokerIndex = index;
            tasks.add(new Callable<OpenedSession>() {
                @Override
                public OpenedSession call() {
                    try {
                        return new OpenedSession(brokerIndex,
                                openSession(brokerIndex, brokerUrls.get(brokerIndex)), null);
                    } catch (Exception e) {
                        return new OpenedSession(brokerIndex, null, e);
                    }
                }
            });
        }
        try {
            List<Future<OpenedSession>> futures = connectorExecutor.invokeAll(
                    tasks, CONNECTION_TIMEOUT + 2_000L, TimeUnit.MILLISECONDS);
            for (Future<OpenedSession> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }
                OpenedSession opened = future.get();
                if (opened.session != null) {
                    sessions.putIfAbsent(opened.brokerIndex, opened.session);
                } else if (opened.error != null) {
                    logger.warn("Failed to create config-v2 Broker {} session",
                            opened.brokerIndex, opened.error);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Failed to initialize config-v2 Broker sessions", e);
        } finally {
            connectorExecutor.shutdownNow();
        }
    }

    private void handleOpened(int brokerIndex, ClientSession session) {
        if (closed) {
            closeQuietly(session);
            return;
        }
        sessions.put(brokerIndex, session);
        logger.info("Connected to config-v2 Broker {}: {}", brokerIndex, session.sessionId());
        final Runnable listener = reconnectListener;
        if (listener != null && callbackExecutor != null) {
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (!closed) {
                        listener.run();
                    }
                }
            });
        }
    }

    private void handleClosed(int brokerIndex, ClientSession session) {
        logger.info("Disconnected from config-v2 Broker {}: {}; Socket.D will reconnect",
                brokerIndex, session.sessionId());
        final Runnable listener = reconnectListener;
        if (!closed && listener != null && callbackExecutor != null
                && !availableSessions().isEmpty()) {
            callbackExecutor.execute(listener);
        }
    }

    private List<Map.Entry<Integer, ClientSession>> availableSessions() {
        List<Map.Entry<Integer, ClientSession>> result =
                new ArrayList<Map.Entry<Integer, ClientSession>>();
        for (Map.Entry<Integer, ClientSession> entry : sessions.entrySet()) {
            ClientSession session = entry.getValue();
            if (session != null && session.isActive() && !session.isClosing()) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<String> buildBrokerUrls(
            List<String> addresses, String namespace, String group) {
        String subscriber = "config:" + namespace + ":" + group;
        String params = "@=" + encode(subscriber)
                + "&namespace=" + encode(namespace)
                + "&group=" + encode(group)
                + "&clientId=" + encode(identity.getClientId())
                + "&applicationName=" + encode(identity.getApplicationName())
                + "&clientVersion=" + encode(ClientIdentity.CLIENT_VERSION);
        Set<String> unique = new LinkedHashSet<String>();
        for (String rawAddress : addresses) {
            if (rawAddress == null || rawAddress.trim().isEmpty()) {
                continue;
            }
            String address = rawAddress.trim();
            address = address.startsWith("sd:") ? address : "sd:ws://" + address;
            if (!address.contains("/config-v2")) {
                address += "/config-v2";
            }
            unique.add(address + (address.contains("?") ? "&" : "?") + params);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be blank");
        }
        return new ArrayList<String>(unique);
    }

    private ExecutorService newDaemonPool(int size, final String threadName) {
        return Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private long parseRevision(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    private void precloseQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            if (session.isActive() && !session.isClosing()) {
                session.preclose();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setOnReconnect(Runnable listener) {
        this.reconnectListener = listener;
    }

    @Override
    public void close() {
        closed = true;
        for (ClientSession session : sessions.values()) {
            precloseQuietly(session);
        }
        for (ClientSession session : sessions.values()) {
            closeQuietly(session);
        }
        sessions.clear();
        if (operationExecutor != null) {
            operationExecutor.shutdownNow();
        }
        if (callbackExecutor != null) {
            callbackExecutor.shutdownNow();
        }
    }

    private static class OpenedSession {
        private final int brokerIndex;
        private final ClientSession session;
        private final Exception error;

        private OpenedSession(int brokerIndex, ClientSession session, Exception error) {
            this.brokerIndex = brokerIndex;
            this.session = session;
            this.error = error;
        }
    }

    private static class FetchResult {
        private final int brokerIndex;
        private final ConfigSnapshot snapshot;
        private final Exception error;

        private FetchResult(int brokerIndex, ConfigSnapshot snapshot, Exception error) {
            this.brokerIndex = brokerIndex;
            this.snapshot = snapshot;
            this.error = error;
        }

        private static FetchResult success(int brokerIndex, ConfigSnapshot snapshot) {
            return new FetchResult(brokerIndex, snapshot, null);
        }

        private static FetchResult failure(int brokerIndex, Exception error) {
            return new FetchResult(brokerIndex, null, error);
        }
    }
}
