package com.xuantong.client.transport.impl;

import com.xuantong.client.exception.XuantongException;
import com.xuantong.client.transport.ConfigTransport;
import com.xuantong.client.transport.ResizableBlockingQueue;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket.D传输实现 - 支持动态连接池调整和泄漏检测
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);
    private static final int INITIAL_POOL_SIZE = 5;
    private static final int MAX_POOL_SIZE = 50;
    // 服务器轮询索引
    private final AtomicInteger currentServerIndex = new AtomicInteger(0);
    private static final int MIN_POOL_SIZE = 3;
    private static final long HEARTBEAT_INTERVAL = 20_000; // 20秒
    private static final long LEAK_CHECK_INTERVAL = 60_000; // 1分钟检查一次
    private static final long LOAD_CHECK_INTERVAL = 30_000; // 30秒检查一次负载
    private static final long CONNECTION_TIMEOUT = 30_000; // 30秒连接超时
    private static final double HIGH_LOAD_THRESHOLD = 0.7; // 70%使用率算高负载
    private static final double LOW_LOAD_THRESHOLD = 0.2; // 20%使用率算低负载

    private final ResizableBlockingQueue<ClientSession> connectionPool;
    private ConfigTransport.ConfigChangeListener configChangeListener;
    private List<String> serverUrls;

    // 连接泄漏检测
    private final Map<ClientSession, Long> borrowedConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poolMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "connection-pool-monitor");
        thread.setDaemon(true);
        return thread;
    });

    public SocketDTransport() {
        this.connectionPool = new ResizableBlockingQueue<>(INITIAL_POOL_SIZE, MAX_POOL_SIZE);
    }

    @Override
    public void connect(List<String> serverAddress, List<String> appNames, String env, ConfigTransport.ConfigChangeListener listener) {
        try {
            this.configChangeListener = listener;
            this.serverUrls = new ArrayList<>(serverAddress.size());
            serverAddress.forEach(address -> serverUrls.add("sd:ws://" + address + "/config?app=" + appNames + "&env=" + env));

            // 初始化连接池
            for (int i = 0; i < INITIAL_POOL_SIZE; i++) {
                createAndAddConnection();
            }

            // 检查是否至少有一个有效连接
            if (connectionPool.isEmpty()) {
                throw new XuantongException("No valid connections available after initialization");
            }

            // 启动监控任务
            startPoolMonitor();
            startLeakDetector();

            if (configChangeListener != null) {
                logger.info("Config change listener registered for {}/{}", appName, env);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize connection pool. Server URLs: {}", serverAddress, e);
            throw new XuantongException("Connection pool initialization failed", e);
        }
    }

    @Override
    public String fetchAllForApps(List<String> appNames, String env) {
        if (appNames == null || appNames.isEmpty()) {
            return "{}";
        }

        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"batch_all\"}")
                    .metaPut("apps", String.join(",", appNames))
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/batch_all", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch batch configs via Socket.D", e);
            return "{}";
        } finally {
            if (session != null) {
                try {
                    returnConnection(session);
                } catch (Exception e) {
                    logger.warn("Error returning connection", e);
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

            Entity response = session.sendAndRequest("/changes", request).await();
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
            throw new XuantongException("No available connection in pool");
        }
        // 记录借出时间和连接
        borrowedConnections.put(session, System.currentTimeMillis());
        return session;
    }

    /**
     * 归还连接到连接池
     */
    private void returnConnection(ClientSession session) {
        try {
            if (session != null) {
                borrowedConnections.remove(session); // 移除泄漏检测记录
                if (!connectionPool.offer(session)) {
                    logger.warn("Failed to return connection to pool, closing it");
                    session.close();
                }
            }
        } catch (Exception e) {
            logger.warn("Error returning connection to pool", e);
        }
    }

    /**
     * 启动连接池监控器
     */
    private void startPoolMonitor() {
        poolMonitor.scheduleAtFixedRate(() -> {
            try {
                adjustPoolSizeBasedOnLoad();
            } catch (Exception e) {
                logger.warn("Pool monitor task failed", e);
            }
        }, LOAD_CHECK_INTERVAL, LOAD_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * 启动连接泄漏检测器
     */
    private void startLeakDetector() {
        poolMonitor.scheduleAtFixedRate(() -> {
            try {
                detectConnectionLeaks();
            } catch (Exception e) {
                logger.warn("Leak detector task failed", e);
            }
        }, LEAK_CHECK_INTERVAL, LEAK_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * 基于负载动态调整连接池大小
     */
    private void adjustPoolSizeBasedOnLoad() {
        int currentSize = connectionPool.size();
        int maxSize = connectionPool.remainingCapacity() + currentSize;
        double utilization = (double) currentSize / maxSize;

        if (utilization >= HIGH_LOAD_THRESHOLD && maxSize < MAX_POOL_SIZE) {
            // 高负载，扩容
            int newSize = Math.min(maxSize + 2, MAX_POOL_SIZE);
            connectionPool.resize(newSize);
            logger.info("Pool expanded from {} to {} due to high load ({}% utilization)", maxSize, newSize, (int) (utilization * 100));

            // 创建新连接
            createAdditionalConnections(newSize - maxSize);

        } else if (utilization <= LOW_LOAD_THRESHOLD && maxSize > MIN_POOL_SIZE) {
            // 低负载，缩容
            int newSize = Math.max(maxSize - 1, MIN_POOL_SIZE);
            connectionPool.resize(newSize);
            logger.info("Pool shrunk from {} to {} due to low load ({}% utilization)", maxSize, newSize, (int) (utilization * 100));
        }
    }

    /**
     * 创建额外的连接
     */
    private void createAdditionalConnections(int count) {
        for (int i = 0; i < count; i++) {
            createAndAddConnection();
        }
    }

    /**
     * 检测连接泄漏
     */
    private void detectConnectionLeaks() {
        long now = System.currentTimeMillis();
        borrowedConnections.entrySet().removeIf(entry -> {
            long borrowTime = entry.getValue();
            if (now - borrowTime > CONNECTION_TIMEOUT) {
                ClientSession session = entry.getKey();
                logger.warn("Potential connection leak detected - session borrowed {}ms ago", now - borrowTime);
                try {
                    session.close(); // 强制关闭泄漏的连接
                } catch (Exception e) {
                    logger.warn("Failed to close leaked connection", e);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 获取下一个服务器URL（轮询负载均衡）
     */
    private String getNextServerUrl() {
        if (serverUrls == null || serverUrls.isEmpty()) {
            return null;
        }
        int index = currentServerIndex.getAndUpdate(i -> (i + 1) % serverUrls.size());
        return serverUrls.get(index);
    }

    /**
     * 创建并添加到连接池
     */
    private void createAndAddConnection() {
        String url = getNextServerUrl();
        if (url != null) {
            try {
                ClientSession session = createConnection(url);
                if (session != null && connectionPool.offer(session)) {
                    logger.debug("New connection added to pool from {}", url);
                } else if (session != null) {
                    session.close();
                }
            } catch (Exception e) {
                logger.error("Failed to create connection to {}", url, e);
            }
        }
    }

    /**
     * 创建SocketD连接
     */
    private ClientSession createConnection(String url) {
        try {
            ClientSession session = SocketD.createClient(url).config(c ->
                            c.heartbeatInterval(HEARTBEAT_INTERVAL))
                    .listen(new EventListener().doOnMessage((s, m) -> {
                if (configChangeListener != null && m != null) {
                    configChangeListener.onChanged(m.dataAsString());
                }
            })).openOrThow();

            logger.info("Created new Socket.D connection to {}", url);
            return session;
        } catch (Throwable e) {
            logger.error("Failed to create Socket.D connection to {}", url, e);
            return null;
        }
    }

    @Override
    public void close() {
        // 停止监控任务
        poolMonitor.shutdown();
        try {
            if (!poolMonitor.awaitTermination(5, TimeUnit.SECONDS)) {
                poolMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            poolMonitor.shutdownNow();
        }

        // 关闭所有连接
        List<ClientSession> sessions = new ArrayList<>();
        connectionPool.drainTo(sessions);
        borrowedConnections.clear();

        sessions.forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing session", e);
            }
        });
    }
}