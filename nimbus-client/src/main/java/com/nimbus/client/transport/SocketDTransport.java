package com.nimbus.client.transport;

import com.nimbus.client.ConfigClientFactory;
import com.nimbus.client.serializer.Serializer;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.EntityMetas;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Socket.D传输实现 (支持连接池和心跳检测)
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);
    private static final int INITIAL_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 10;
    private static final int MIN_POOL_SIZE = 1;
    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒
    private static final long HEALTH_CHECK_INTERVAL = 60000; // 60秒
    private final List<String> serverUrls;
    private final String appName;
    private final String env;
    private final Serializer serializer;
    private final ResizableBlockingQueue<ClientSession> connectionPool;
    private final AtomicReference<Map<String, String>> configCache = new AtomicReference<>(new ConcurrentHashMap<>());
    private final ScheduledExecutorService healthCheckScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConfigClientFactory configClientFactory;

    /**
     * 从连接池获取可用连接
     */
    private ClientSession getConnection() throws InterruptedException {
        ClientSession session = connectionPool.poll(5, TimeUnit.SECONDS);
        if (session == null) {
            throw new RuntimeException("No available connection in pool");
        }
        return session;
    }

    public SocketDTransport(List<String> serverAddrs, String appName, String env, ConfigClientFactory configClientFactory) {
        this.serverUrls = new ArrayList<>();
        for (String addr : serverAddrs) {
            // 确保地址格式正确：sd:ws://host:port/path
            String formattedAddr = addr.startsWith("sd:") ? addr : "sd:" + addr;
            this.serverUrls.add(formattedAddr + "/config?app=" + appName + "&env=" + env);
        }
        this.appName = appName;
        this.env = env;
        this.serializer = Serializer.defaultSerializer();
        this.connectionPool = new ResizableBlockingQueue<>(INITIAL_POOL_SIZE, MAX_POOL_SIZE);
        this.configClientFactory = configClientFactory;

        // 启动健康检查
        healthCheckScheduler.scheduleAtFixedRate(this::healthCheck,
            HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    public void connect() {
        try {
            // 初始化连接池 - 只创建初始数量的连接
            for (int i = 0; i < INITIAL_POOL_SIZE; i++) {
                createAndAddConnection();
            }

            // 检查是否至少有一个有效连接
            if (connectionPool.isEmpty()) {
                throw new RuntimeException("No valid connections available after initialization");
            }

            // 请求初始配置
            Entity request = new StringEntity("{\"action\":\"init\"}")
                    .metaPut("app", appName)
                    .metaPut("env", env);

            ClientSession session = getConnection();
            try {
                Entity response = session.sendAndRequest("init", request).await();
                Map<String, String> initialConfigs = serializer.deserializeMap(response.dataAsString());
                configCache.set(initialConfigs);
                logger.info("Connected to config servers, fetched {} configs", initialConfigs.size());
            } catch (Exception e) {
                logger.error("Failed to fetch initial configs from server", e);
                throw new RuntimeException("Initial config fetch failed: " + e.getMessage(), e);
            } finally {
                returnConnection(session);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize connection pool. Server URLs: {}", serverUrls, e);
            throw new RuntimeException("Connection pool initialization failed", e);
        }
    }
    /**
     * 创建SocketD连接
     */
    private ClientSession createConnection(String url) throws IOException {
        EventListener listener = new EventListener()
                .doOnOpen(session -> logger.info("Connection opened: {}", session.sessionId()))
                .doOn("push", (session, message) -> handleConfigPush(message))
                .doOnClose(session -> logger.info("Connection closed: {}", session.sessionId()))
                .doOnError((session, error) -> logger.error("Connection error", error));

        return SocketD.createClient(url)
                .config(c -> c
                    .heartbeatInterval(HEARTBEAT_INTERVAL)
                    .streamTimeout(30_000L) // 增加流超时到30秒
                )
                .listen(listener)
                .openOrThow();
    }

    private void createAndAddConnection() {
        for (String url : serverUrls) {
            try {
                logger.debug("Attempting to connect to: {}", url);
                ClientSession session = createConnection(url);

                logger.info("Successfully connected to: {}", url);
                if (connectionPool.offer(session)) {
                    logger.debug("Connection added to pool, current size: {}", connectionPool.size());
                    return;
                } else {
                    logger.warn("Connection pool full, closing connection");
                    session.close();
                }
            } catch (Exception e) {
                logger.error("Failed to connect to {}: {}", url, e.getMessage(), e);
            }
        }
        logger.error("All connection attempts failed. Server URLs: {}", serverUrls);
        throw new RuntimeException("Failed to create any connection");
    }

    private ClientSession createConnectionWithRetry() {
        int maxRetries = 3;
        int retryDelay = 1000; // 1秒

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                for (String url : serverUrls) {
                    try {
                        ClientSession session = createConnection(url);
                        logger.info("Successfully connected to {}", url);
                        return session;
                    } catch (Exception e) {
                        logger.warn("Failed to connect to {} (attempt {}/{})", url, attempt, maxRetries, e);
                    }
                }

                if (attempt < maxRetries) {
                    Thread.sleep((long) retryDelay * attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Connection retry interrupted", e);
            } catch (Exception e) {
                logger.error("Unexpected error during connection retry", e);
            }
        }

        throw new RuntimeException("Failed to create connection after " + maxRetries + " attempts");
    }

    private void returnConnection(ClientSession session) throws IOException {
        if (session != null && session.isValid()) {
            if (!connectionPool.offer(session)) {
                session.close();
            }
        }
    }

    private void handleConfigPush(Message message) {
        try {
            String configJson = message.dataAsString();
            Map<String, String> newConfigs = serializer.deserializeMap(configJson);

            // 使用原子操作增量更新缓存
            Map<String, String> currentConfigs = configCache.get();
            Map<String, String> updatedConfigs = new ConcurrentHashMap<>(currentConfigs);
            updatedConfigs.putAll(newConfigs);
            configCache.set(updatedConfigs);

            logger.info("Config updated via push: {} configs changed, total now: {}",
                newConfigs.size(), updatedConfigs.size());

            // 通知ConfigClientFactory处理配置推送（包括本地缓存和快照更新）
            if (configClientFactory != null) {
                try {
                    configClientFactory.handleConfigPush(newConfigs);
                    logger.debug("Config push handled by factory");
                } catch (Exception e) {
                    logger.warn("Failed to handle config push via factory", e);
                    // 如果factory处理失败，直接更新快照作为备选
                    try {
                        configClientFactory.updatePartialSnapshot(newConfigs);
                    } catch (Exception fallbackError) {
                        logger.error("Fallback snapshot update also failed", fallbackError);
                    }
                }
            } else {
                logger.warn("ConfigClientFactory not available to handle config push");
            }
        } catch (Exception e) {
            logger.error("Failed to handle config push", e);
        }
    }

    @Override
    public Map<String, String> fetchAll() {
        return new ConcurrentHashMap<>(configCache.get());
    }
    @Override
    public Map<String, String> fetchChanges() {
        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"changes\"}")
                    .metaPut("app", appName)
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("changes", request)
                                   .await();
            Map<String, String> changes = serializer.deserializeMap(response.dataAsString());
            logger.info("Fetched {} config changes", changes.size());
            return changes;
        } catch (Exception e) {
            logger.error("Failed to fetch config changes via Socket.D", e);
            return new ConcurrentHashMap<>();
        } finally {
            if (session != null) {
                try {
                    returnConnection(session);
                } catch (Exception e) {
                    logger.warn("Failed to return connection to pool", e);
                }
            }
        }
    }

    @Override
    public String fetch(String key) {
        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"get\",\"key\":\"" + key + "\"}")
                    .metaPut("app", appName)
                    .metaPut("env", env)
                    .metaPut("key", key);

            // 单个key获取
            Entity response = session.sendAndRequest("get", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch config '{}' via Socket.D", key, e);
            return null;
        } finally {
            if (session != null) {
                try {
                    returnConnection(session);
                } catch (Exception e) {
                    logger.warn("Failed to return connection to pool", e);
                }
            }
        }
    }

    @Override
    public Serializer getSerializer() {
        return serializer;
    }

    private void healthCheck() {
        try {
            List<ClientSession> sessions = new ArrayList<>();
            connectionPool.drainTo(sessions);

            // 检查连接健康状态
            sessions.forEach(session -> {
                try {
                    if (!session.isValid()) {
                        logger.info("Removing invalid session: {}", session.sessionId());
                        session.close();
                        return;
                    }

                    // 简单的心跳检测
                    session.send("/ping", new StringEntity("{\"action\":\"ping\"}"));

                    // 重新放入连接池
                    if (!connectionPool.offer(session)) {
                        session.close();
                    }
                } catch (Exception e) {
                    logger.warn("Health check failed for session: {}", session.sessionId(), e);
                    try {
                        session.close();
                    } catch (Exception ex) {
                        logger.error("Error closing unhealthy session", ex);
                    }
                }
            });

            // 基于队列使用率的简单调整
            adjustPoolSize();
        } catch (Exception e) {
            logger.error("Health check scheduler error", e);
        }
    }

    private void adjustPoolSize() {
        int currentSize = connectionPool.size();
        if (currentSize < MIN_POOL_SIZE) {
            // 低于最小连接数，补充连接
            int toAdd = MIN_POOL_SIZE - currentSize;
            for (int i = 0; i < toAdd; i++) {
                createAndAddConnection();
            }
        } else {
            // 基于队列使用率的简单调整
            double usageRate = (double) currentSize / MAX_POOL_SIZE;
            if (usageRate > 0.7 && currentSize < MAX_POOL_SIZE) {
                // 高使用率时扩容
                connectionPool.resize(Math.min(MAX_POOL_SIZE, currentSize + 1));
            } else if (usageRate < 0.3 && currentSize > MIN_POOL_SIZE) {
                // 低使用率时缩容
                connectionPool.resize(Math.max(MIN_POOL_SIZE, currentSize - 1));
            }
        }
    }

    @Override
    public void close() {
        healthCheckScheduler.shutdown();
        try {
            if (!healthCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                healthCheckScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<ClientSession> sessions = new ArrayList<>();
        connectionPool.drainTo(sessions);
        sessions.forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                logger.error("Error closing session", e);
            }
        });
    }
}