package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.serializer.Serializer;
import cloud.xuantong.client.transport.ConfigTransport;
import cloud.xuantong.client.transport.ResizableBlockingQueue;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
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
    private static final long DEDUP_WINDOW_MS = 5000; // 5秒去重窗口

    private final ResizableBlockingQueue<ClientSession> connectionPool;
    private ConfigTransport.ConfigChangeListener configChangeListener;
    private List<String> serverUrls;

    // 连接泄漏检测
    private final Map<ClientSession, Long> borrowedConnections = new ConcurrentHashMap<>();
    // 消息去重缓存（防止集群重复消息）
    private final Map<String, Long> messageDeduplicationCache = new ConcurrentHashMap<>();
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
            serverAddress.forEach(address -> serverUrls.add("sd:ws://" + address + "/config?env=" + env + "&appNames=" + String.join("_", appNames)));

            // 初始化连接池
            for (int i = 0; i < INITIAL_POOL_SIZE; i++) {
                initConnection();
            }

            // 检查是否至少有一个有效连接
            if (connectionPool.isEmpty()) {
                throw new XuantongException("No valid connections available after initialization");
            }

            // 启动监控任务
            startPoolMonitor();
            startLeakDetector();

            if (configChangeListener != null) {
                logger.info("Config change listener registered for {}/{}", appNames, env);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize connection pool. Server URLs: {}", serverAddress, e);
            throw new XuantongException("Connection pool initialization failed", e);
        }
    }

    /**
     * 创建SocketD连接
     */
    private ClientSession createConnection(String url) {
        try {
            //使用非集群方式 去中心化，自己维护连接 负载
            ClientSession session = SocketD.createClient(url).config(c ->
                            c.heartbeatInterval(HEARTBEAT_INTERVAL)
                                    .connectTimeout(CONNECTION_TIMEOUT)
                                    .autoReconnect(true))
                    .listen(new EventListener()
                            .doOn("/push", (s, m) -> {
                                if (configChangeListener != null && m != null) {
                                    String message = m.dataAsString();
                                    // 使用消息内容的MD5作为去重标识
                                    String messageHash = generateMessageHash(message);
                                    long now = System.currentTimeMillis();
                                    Long lastSeen = messageDeduplicationCache.get(messageHash);
                                    if (lastSeen == null || now - lastSeen > DEDUP_WINDOW_MS) {
                                        messageDeduplicationCache.put(messageHash, now);
                                        configChangeListener.onChanged(message);
                                    } else {
                                        logger.debug("Duplicate config change message ignored (hash: {})", messageHash.substring(0, 8));
                                    }
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

    @Deprecated
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
    public String fetch(String key, String env) {
        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity("{\"action\":\"get\",\"key\":\"" + key + "\"}")
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
        int currentMaxSize = connectionPool.size() + connectionPool.remainingCapacity();
        // 活跃连接数 = 已借出的连接数（从borrowedConnections获取）
        int activeConnections = borrowedConnections.size();
        double utilization = currentMaxSize > 0 ? (double) activeConnections / currentMaxSize : 0.0;

        if (utilization >= HIGH_LOAD_THRESHOLD && currentMaxSize < MAX_POOL_SIZE) {
            // 高负载，扩容
            int newSize = Math.min(currentMaxSize + 2, MAX_POOL_SIZE);
            connectionPool.resize(newSize);
            logger.info("Pool expanded from {} to {} due to high load ({} active/{} max, {}% utilization)",
                    currentMaxSize, newSize, activeConnections, currentMaxSize, (int) (utilization * 100));

            // 创建新连接
            createAdditionalConnections(newSize - currentMaxSize);

        } else if (utilization <= LOW_LOAD_THRESHOLD && currentMaxSize > MIN_POOL_SIZE) {
            // 低负载，缩容
            int newSize = Math.max(currentMaxSize - 1, MIN_POOL_SIZE);
            connectionPool.resize(newSize);
            logger.info("Pool shrunk from {} to {} due to low load ({} active/{} max, {}% utilization)",
                    currentMaxSize, newSize, activeConnections, currentMaxSize, (int) (utilization * 100));
        }
    }

    /**
     * 创建额外的连接
     */
    private void createAdditionalConnections(int count) {
        for (int i = 0; i < count; i++) {
            try {
                String serverUrl = getNextServerUrl();
                if (serverUrl == null) {
                    logger.warn("No available server url");
                    break;
                }
                ClientSession session = createConnection(serverUrl);
                if (session != null && connectionPool.offer(session)) {
                    logger.debug("New connection added to pool from {}", serverUrl);
                } else if (session != null) {
                    session.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to create connection to pool", e);
            }
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
     * 基于综合负载的智能均衡策略
     */
    private String getNextServerUrl() {
        if (serverUrls == null || serverUrls.isEmpty()) {
            return null;
        }

        if (serverUrls.size() == 1) {
            return serverUrls.get(0);
        }

        // 收集所有服务器的负载信息
        Map<String, ServerLoadInfo> serverLoads = new HashMap<>();
        for (String url : serverUrls) {
            ServerLoadInfo loadInfo = pingServer(url);
            if (loadInfo != null) {
                serverLoads.put(url, loadInfo);
            }
        }

        if (serverLoads.isEmpty()) {
            // 所有服务器都不可用，降级到随机选择
            return serverUrls.get(new Random().nextInt(serverUrls.size()));
        }

        // 基于综合指标选择最佳服务器
        return selectBestServerByLoad(serverLoads);
    }

    /**
     * 基于综合负载选择最佳服务器
     */
    private String selectBestServerByLoad(Map<String, ServerLoadInfo> serverLoads) {
        // 计算总权重
        double totalWeight = 0;
        Map<String, Double> weights = new HashMap<>();

        for (Map.Entry<String, ServerLoadInfo> entry : serverLoads.entrySet()) {
            ServerLoadInfo load = entry.getValue();

            // 权重计算（响应时间权重 + 负载权重）
            double responseWeight = 1000.0 / (load.responseTime + 1); // +1避免除零
            double loadWeight = 100.0 * (1 - load.getLoadFactor()); // 负载越低权重越高

            // 综合权重（60%响应时间 + 40%负载）
            double weight = responseWeight * 0.6 + loadWeight * 0.4;

            weights.put(entry.getKey(), weight);
            totalWeight += weight;
        }

        // 加权随机选择
        double randomValue = new Random().nextDouble() * totalWeight;
        double cumulativeWeight = 0;

        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            cumulativeWeight += entry.getValue();
            if (randomValue < cumulativeWeight) {
                return entry.getKey();
            }
        }

        return serverLoads.keySet().iterator().next();
    }

    /**
     * 向服务器发送ping请求并获取负载信息 结束自动关闭session
     */
    private ServerLoadInfo pingServer(String url) {
        long startTime = System.currentTimeMillis();
        try (ClientSession session = SocketD.createClient(url)
                .config(c -> c.connectTimeout(3000)) // 3秒连接超时
                .open()) {

            Entity response = session.sendAndRequest("/ping", new StringEntity("ping"))
                    .await();

            // 解析响应数据，格式为："responseTime,currentConnections,maxConnections"
            Map<String, Object> map = Serializer.defaultSerializer().deserializeMap(response.dataAsString());
            response.dataAsString();
            long responseTime = System.currentTimeMillis() - startTime;
            long currentConnections = Long.parseLong(map.get("total_sessions").toString());
            long maxConnections = Long.parseLong(map.get("max_sessions").toString());

            logger.debug("Server {} load: {}/{} connections, response: {}ms",
                    url, currentConnections, maxConnections, responseTime);

            return new ServerLoadInfo(responseTime, currentConnections, maxConnections);

        } catch (Exception e) {
            logger.warn("Server {} ping failed: {}", url, e.getMessage());
            return null; // 表示服务器不可用
        }
    }

    /**
     * 服务器负载信息类
     */
    private static class ServerLoadInfo {
        final long responseTime;
        final long currentConnections;
        final long maxConnections;

        ServerLoadInfo(long responseTime, long currentConnections, long maxConnections) {
            this.responseTime = responseTime;
            this.currentConnections = currentConnections;
            this.maxConnections = maxConnections;
        }

        double getLoadFactor() {
            return (double) currentConnections / maxConnections;
        }
    }


    /**
     * // 使用轮询选择，平均分配
     */
    private void initConnection() {
        String url = selectPollServerUrl();
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
     * 轮询选择服务器（用于连接池初始化，实现平均分配）
     */
    private String selectPollServerUrl() {
        if (serverUrls == null || serverUrls.isEmpty()) {
            return null;
        }
        int index = currentServerIndex.getAndUpdate(i -> (i + 1) % serverUrls.size());
        return serverUrls.get(index);
    }

    /**
     * 生成消息内容的哈希值（用于去重）
     */
    private String generateMessageHash(String message) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to generate message hash, using full message as key", e);
            return message; // 降级方案
        }
    }

    @Override
    public String fetchSpecificKeys(String keys, String env) {
        if (keys == null || keys.isEmpty()) {
            return "{}";
        }

        ClientSession session = null;
        try {
            session = getConnection();
            Entity request = new StringEntity(keys)
                    .metaPut("action", "batch_keys")
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/batch_keys", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch specific keys via Socket.D", e);
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
        messageDeduplicationCache.clear();

        sessions.forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing session", e);
            }
        });
    }
}