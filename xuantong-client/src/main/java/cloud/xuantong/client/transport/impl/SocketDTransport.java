package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.transport.ConfigTransport;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket.D 传输实现（Broker 模式 + Failover）
 * <p>
 * 设计原则：
 * - 支持多 Broker 地址，自动 failover
 * - @=name 自动注册到 Broker（服务发现）
 * - Broker 负责负载均衡和消息路由
 * - 监听 /config-change 接收配置变更推送
 * <p>
 * Failover 策略：
 * 1. 初始化时尝试连接所有 Broker，选第一个可用的
 * 2. 连接断开时，自动切换到下一个可用 Broker（指数退避）
 * 3. 所有 Broker 都不可用时，后台持续重试
 * <p>
 * 连接 URL 格式:
 * sd:ws://host:port/path?@=project:env
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);

    private static final long HEARTBEAT_INTERVAL = 20_000;  // 20秒心跳
    private static final long CONNECTION_TIMEOUT = 30_000;  // 30秒连接超时
    private static final int MAX_RETRIES = 2;               // 每个 Broker 最大重试次数
    // 指数退避: 1s → 2s → 4s → 8s → 16s → 30s（封顶）
    private static final long RECONNECT_BASE_INTERVAL = 1_000;
    private static final long RECONNECT_MAX_INTERVAL = 30_000;

    private volatile ClientSession session;
    private ConfigChangeListener configChangeListener;
    private Runnable onReconnectListener;
    private List<String> brokerUrls;
    private String playerName;
    private final AtomicInteger currentBrokerIndex = new AtomicInteger(0);
    private volatile boolean closed = false;
    private volatile boolean initialConnected = false;
    private Thread reconnectThread;

    @Override
    public void connect(List<String> serverAddress, List<String> appNames, String env, String secretKey, ConfigChangeListener listener) {
        this.configChangeListener = listener;
        // Player 名用环境名，Broker 按环境组播推送
        this.playerName = env;

        // 构建所有 Broker 的连接 URL
        // 格式: sd:ws://host:port?@=env&apps=app1,app2&token=xxx
        String appsParam = String.join(",", appNames);
        StringBuilder params = new StringBuilder("@=").append(playerName).append("&apps=").append(appsParam);
        if (secretKey != null && !secretKey.isEmpty()) {
            params.append("&token=").append(secretKey);
        }

        this.brokerUrls = new ArrayList<>();
        for (String address : serverAddress) {
            if (!address.startsWith("sd:")) {
                address = "sd:ws://" + address;
            }
            if (!address.contains("/config")) {
                address = address + "/config";
            }
            String separator = address.contains("?") ? "&" : "?";
            this.brokerUrls.add(address + separator + params);
        }

        // 尝试连接到任一可用 Broker
        connectToAnyBroker();
    }

    /**
     * 尝试连接到任一可用 Broker
     * 遍历所有 Broker 地址，连接第一个可用的
     */
    private void connectToAnyBroker() {
        Exception lastException = null;

        for (int i = 0; i < brokerUrls.size(); i++) {
            int index = (currentBrokerIndex.get() + i) % brokerUrls.size();
            String url = brokerUrls.get(index);

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    this.session = createSession(url);
                    currentBrokerIndex.set(index);
                    logger.info("Connected to Broker ({}): {}", index, url);
                    return;
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Broker {} attempt {}/{} failed: {}", index, attempt, MAX_RETRIES, e.getMessage());
                }
            }
        }

        // 所有 Broker 都连接失败，启动后台重连（不抛异常，让应用继续启动）
        logger.warn("All Brokers unreachable, starting background reconnect. App will continue without initial config.");
        startBackgroundReconnect();
    }

    /**
     * 创建 Socket.D 会话
     * <p>
     * 关闭 Socket.D 内置 autoReconnect，完全由 startBackgroundReconnect 管理重连。
     * 避免 autoReconnect（只能重连同一 Broker）与自定义重连（可切换 Broker）同时触发，
     * 导致产生两个活跃 session 的冲突问题。
     */
    private ClientSession createSession(String url) {
        try {
            return SocketD.createClient(url)
                    .config(c -> c.heartbeatInterval(HEARTBEAT_INTERVAL)
                            .connectTimeout(CONNECTION_TIMEOUT)
                            .autoReconnect(false))
                    .listen(new EventListener()
                            .doOn("/config-change", (s, m) -> {
                                if (configChangeListener != null && m != null) {
                                    try {
                                        String message = m.dataAsString();
                                        logger.debug("Received config change: {}", message);
                                        configChangeListener.onChanged(message);
                                    } catch (Exception e) {
                                        logger.error("Failed to process config change", e);
                                    }
                                }
                            })
                            .doOnOpen(s -> {
                                logger.info("Broker connection opened: {}", s.sessionId());
                                // 仅标记初始连接完成；reload 由 startBackgroundReconnect 在 session 赋值后显式触发
                                // 避免 doOnOpen 在 openOrThow 返回前触发、this.session 尚未赋值的时序问题
                                initialConnected = true;
                            })
                            .doOnClose(s -> {
                                logger.warn("Broker connection closed: {}", s.sessionId());
                                // 只有当前活跃 session 关闭才触发重连，旧 session 的关闭事件忽略
                                if (!closed && s == session) {
                                    startBackgroundReconnect();
                                }
                            })
                            .doOnError((s, e) -> logger.error("Broker connection error: {}", s.sessionId(), e)))
                    .openOrThow();
        } catch (IOException e) {
            throw new XuantongException("Failed to open Broker connection", e);
        }
    }

    /**
     * 启动后台重连线程（指数退避 + Broker 轮转）
     * <p>
     * 重连策略：
     * - 每个 Broker 尝试 MAX_RETRIES 次，失败后切换下一个
     * - 所有 Broker 都失败后，指数退避等待：1s → 2s → 4s → ... → 30s（封顶）
     * - 连接成功后重置退避计数器
     */
    private synchronized void startBackgroundReconnect() {
        if (closed || reconnectThread != null) {
            return;
        }

        reconnectThread = new Thread(() -> {
            logger.info("Background reconnect started");
            int backoffMultiplier = 1;

            while (!closed) {
                boolean connected = false;

                // 轮询所有 Broker，每个尝试 MAX_RETRIES 次
                for (int i = 0; i < brokerUrls.size() && !closed; i++) {
                    int index = (currentBrokerIndex.get() + i) % brokerUrls.size();
                    String url = brokerUrls.get(index);

                    for (int attempt = 1; attempt <= MAX_RETRIES && !closed; attempt++) {
                        try {
                            ClientSession oldSession = this.session;
                            this.session = createSession(url);
                            if (oldSession != null) {
                                try { oldSession.close(); } catch (Exception ignored) {}
                            }
                            currentBrokerIndex.set(index);
                            logger.info("Reconnected to Broker ({}): {}", index, url);
                            connected = true;
                            break;
                        } catch (Exception e) {
                            logger.debug("Reconnect to Broker {} attempt {}/{} failed: {}",
                                    index, attempt, MAX_RETRIES, e.getMessage());
                        }
                    }
                    if (connected) break;
                }

                if (connected) {
                    backoffMultiplier = 1; // 重置退避
                    synchronized (this) {
                        reconnectThread = null;
                    }
                    // 重连成功后触发配置重载（在 this.session 已赋值之后，避免 doOnOpen 时序问题）
                    if (initialConnected && onReconnectListener != null) {
                        logger.info("Reconnect succeeded, triggering config reload");
                        onReconnectListener.run();
                    }
                    return;
                }

                // 所有 Broker 都失败，指数退避等待
                if (!closed) {
                    long sleepMs = Math.min(RECONNECT_BASE_INTERVAL * backoffMultiplier, RECONNECT_MAX_INTERVAL);
                    try {
                        logger.debug("All Brokers unreachable, waiting {}ms before retry", sleepMs);
                        Thread.sleep(sleepMs);
                        backoffMultiplier = Math.min(backoffMultiplier * 2,
                                (int)(RECONNECT_MAX_INTERVAL / RECONNECT_BASE_INTERVAL));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            synchronized (this) {
                reconnectThread = null;
            }
            logger.info("Background reconnect stopped");
        }, "broker-reconnect");

        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    @Override
    public String fetchAllForApps(List<String> appNames, String env) {
        if (appNames == null || appNames.isEmpty()) {
            return "{}";
        }

        ClientSession currentSession = this.session;
        if (currentSession == null) {
            logger.warn("fetchAllForApps skipped: no active Broker session");
            return null;
        }

        try {
            Entity request = new StringEntity("{}")
                    .metaPut("apps", String.join(",", appNames))
                    .metaPut("env", env);

            Entity response = currentSession.sendAndRequest("/batch_all", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch batch configs via Broker", e);
            return null;
        }
    }

    @Override
    public String fetch(String key, String env) {
        ClientSession currentSession = this.session;
        if (currentSession == null) {
            logger.warn("fetch skipped (key={}): no active Broker session", key);
            return null;
        }

        try {
            Entity request = new StringEntity("{}")
                    .metaPut("env", env)
                    .metaPut("key", key);

            Entity response = currentSession.sendAndRequest("/get", request).await();
            // 服务端用 meta "found" 区分：配置存在（值为空串）vs 配置不存在
            String found = response.meta("found");
            if ("false".equals(found)) {
                return null;
            }
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch config '{}' via Broker", key, e);
            return null;
        }
    }

    @Override
    public String fetchSpecificKeys(String keys, String env) {
        if (keys == null || keys.isEmpty()) {
            return "{}";
        }

        ClientSession currentSession = this.session;
        if (currentSession == null) {
            logger.warn("fetchSpecificKeys skipped: no active Broker session");
            return "{}";
        }

        try {
            Entity request = new StringEntity(keys)
                    .metaPut("action", "batch_keys")
                    .metaPut("env", env);

            Entity response = currentSession.sendAndRequest("/batch_keys", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch specific keys via Broker", e);
            return "{}";
        }
    }

    @Deprecated
    @Override
    public String fetchChanges(String appName, String env) {
        ClientSession currentSession = this.session;
        if (currentSession == null) {
            logger.warn("fetchChanges skipped: no active Broker session");
            return null;
        }

        try {
            Entity request = new StringEntity("{}")
                    .metaPut("app", appName)
                    .metaPut("env", env);

            Entity response = currentSession.sendAndRequest("/changes", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch changes via Broker", e);
            return null;
        }
    }

    @Override
    public void close() {
        this.closed = true;

        // 停止重连线程
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }

        if (session != null) {
            try {
                session.close();
                logger.info("Closed Broker connection");
            } catch (Exception e) {
                logger.warn("Error closing Broker connection", e);
            }
        }
    }

    @Override
    public void setOnReconnect(Runnable listener) {
        this.onReconnectListener = listener;
    }
}
