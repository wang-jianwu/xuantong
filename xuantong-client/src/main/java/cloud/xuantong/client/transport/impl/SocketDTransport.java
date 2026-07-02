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
 * 2. 连接断开时，自动切换到下一个可用 Broker
 * 3. 所有 Broker 都不可用时，后台持续重试
 * <p>
 * 连接 URL 格式:
 * sd:ws://host:port/path?@=project:env
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);

    private static final long HEARTBEAT_INTERVAL = 20_000;  // 20秒心跳
    private static final long CONNECTION_TIMEOUT = 30_000;  // 30秒连接超时
    private static final int MAX_RETRIES = 3;               // 每个 Broker 最大重试次数
    private static final long RECONNECT_INTERVAL = 5_000;   // 重连间隔

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
     */
    private ClientSession createSession(String url) {
        try {
            return SocketD.createClient(url)
                    .config(c -> c.heartbeatInterval(HEARTBEAT_INTERVAL)
                            .connectTimeout(CONNECTION_TIMEOUT)
                            .autoReconnect(true))
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
                                // 初始连接后再次打开才视为重连，避免与初始化流程重复
                                if (initialConnected && onReconnectListener != null) {
                                    logger.info("Reconnect detected, triggering config reload");
                                    onReconnectListener.run();
                                }
                                initialConnected = true;
                            })
                            .doOnClose(s -> {
                                logger.warn("Broker connection closed: {}", s.sessionId());
                                if (!closed) {
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
     * 启动后台重连线程
     * 当连接断开时，自动尝试连接下一个可用 Broker
     */
    private synchronized void startBackgroundReconnect() {
        if (closed || reconnectThread != null) {
            return;
        }

        reconnectThread = new Thread(() -> {
            logger.info("Background reconnect started");

            while (!closed) {
                // 轮询所有 Broker
                for (int i = 0; i < brokerUrls.size() && !closed; i++) {
                    int index = (currentBrokerIndex.get() + i) % brokerUrls.size();
                    String url = brokerUrls.get(index);

	                    try {
                        ClientSession oldSession = this.session;
                        this.session = createSession(url);
                        // 关闭旧 session
                        if (oldSession != null) {
                            try { oldSession.close(); } catch (Exception ignored) {}
                        }
                        currentBrokerIndex.set(index);
                        logger.info("Reconnected to Broker ({}): {}", index, url);
                        reconnectThread = null;
                        return;
                    } catch (Exception e) {
                        logger.debug("Reconnect to Broker {} failed: {}", index, e.getMessage());
                    }
                }

                // 所有 Broker 都失败，等待后重试
                if (!closed) {
                    try {
                        logger.debug("All Brokers unreachable, waiting {}ms before retry", RECONNECT_INTERVAL);
                        Thread.sleep(RECONNECT_INTERVAL);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            reconnectThread = null;
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

        try {
            Entity request = new StringEntity("{}")
                    .metaPut("apps", String.join(",", appNames))
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/batch_all", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch batch configs via Broker", e);
            return "{}";
        }
    }

    @Override
    public String fetch(String key, String env) {
        try {
            Entity request = new StringEntity("{}")
                    .metaPut("env", env)
                    .metaPut("key", key);

            Entity response = session.sendAndRequest("/get", request).await();
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

        try {
            Entity request = new StringEntity(keys)
                    .metaPut("action", "batch_keys")
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/batch_keys", request).await();
            return response.dataAsString();
        } catch (Exception e) {
            logger.error("Failed to fetch specific keys via Broker", e);
            return "{}";
        }
    }

    @Deprecated
    @Override
    public String fetchChanges(String appName, String env) {
        try {
            Entity request = new StringEntity("{}")
                    .metaPut("app", appName)
                    .metaPut("env", env);

            Entity response = session.sendAndRequest("/changes", request).await();
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
