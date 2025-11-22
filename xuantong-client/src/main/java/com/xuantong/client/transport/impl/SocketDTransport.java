package com.xuantong.client.transport.impl;

import com.xuantong.client.transport.ConfigTransport;
import com.xuantong.client.transport.ResizableBlockingQueue;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Socket.D传输实现
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);
    private static final int INITIAL_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 10;
    private static final int MIN_POOL_SIZE = 1;
    private static final long HEARTBEAT_INTERVAL = 20_000; // 20秒
    private final ResizableBlockingQueue<ClientSession> connectionPool;
    private ConfigTransport.ConfigChangeListener configChangeListener;


    public SocketDTransport() {
        this.connectionPool = new ResizableBlockingQueue<>(INITIAL_POOL_SIZE, MAX_POOL_SIZE);
    }

    @Override
    public void connect(List<String> serverAddress, String appName, String env, ConfigTransport.ConfigChangeListener listener) {
        try {
            this.configChangeListener = listener;
            List<String> urls = new ArrayList<>(serverAddress.size());
            serverAddress.forEach(address -> urls.add("sd:ws://" + address + "/config?app=" + appName + "&env=" + env));
            // 初始化连接池 - 只创建初始数量的连接
            for (int i = 0; i < INITIAL_POOL_SIZE; i++) {
                createAndAddConnection(urls);
            }
            // 检查是否至少有一个有效连接
            if (connectionPool.isEmpty()) {
                throw new RuntimeException("No valid connections available after initialization");
            }

            if (configChangeListener != null) {
                logger.info("Config change listener registered for {}/{}", appName, env);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize connection pool. Server URLs: {}", serverAddress, e);
            throw new RuntimeException("Connection pool initialization failed", e);
        }
    }

    @Override
    public String fetchAll(String appName, String env) {
        // 直接从服务器获取配置，不维护本地缓存
        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"all\"}")
                    .metaPut("app", appName)
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/all", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch all configs via Socket.D", e);
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
    public String fetchChanges(String appName, String env) {
        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"changes\"}")
                    .metaPut("app", appName)
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/changes", request)
                    .await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch config changes via Socket.D", e);
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
    public String fetch(String key, String appName, String env) {
        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"get\",\"key\":\"" + key + "\"}")
                    .metaPut("app", appName)
                    .metaPut("env", env)
                    .metaPut("key", key);

            // 单个key获取
            Entity response = session.sendAndRequest("/get", request).await();
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

    /**
     * 创建SocketD连接
     */
    private ClientSession createConnection(String url) throws IOException {
        EventListener listener = new EventListener()
                .doOnOpen(session -> logger.info("Connection opened: {}", session.sessionId()))
                .doOn("/push", (session, message) -> {
                    Entity entity = message.entity();
                    //变更回调
                    configChangeListener.onChanged(entity.dataAsString());
                })
                .doOnClose(session -> logger.info("Connection closed: {}", session.sessionId()))
                .doOnError((session, error) -> logger.error("Connection error", error));

        return SocketD.createClient(url)
                .config(c -> c
                        .heartbeatInterval(HEARTBEAT_INTERVAL)
                        .streamTimeout(30_000L)
                )
                .listen(listener)
                .openOrThow();
    }

    private void createAndAddConnection(List<String> serverUrls) {
        for (String url : serverUrls) {
            try {
                logger.debug("Attempting to connect to: {}", url);
                ClientSession session = createConnection(url);

                logger.info("Successfully connected to: {}", url);
                if (connectionPool.offer(session)) {
                    logger.debug("Connection added to pool, current size: {}", connectionPool.size());
                } else {
                    logger.warn("Connection pool full, closing connection");
                    session.close();
                }
            } catch (Exception e) {
                logger.error("Failed to connect to {}: {}", url, e.getMessage(), e);
            }
        }
    }

    private void returnConnection(ClientSession session) throws IOException {
        if (session != null && session.isValid()) {
            if (!connectionPool.offer(session)) {
                session.close();
            }
        }
    }

    private void adjustPoolSize(List<String> serverUrls) {
        int currentSize = connectionPool.size();
        if (currentSize < MIN_POOL_SIZE) {
            // 低于最小连接数，补充连接
            int toAdd = MIN_POOL_SIZE - currentSize;
            for (int i = 0; i < toAdd; i++) {
                createAndAddConnection(serverUrls);
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