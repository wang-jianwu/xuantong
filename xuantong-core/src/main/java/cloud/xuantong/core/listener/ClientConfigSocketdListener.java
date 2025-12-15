package cloud.xuantong.core.listener;

import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Listener;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.impl.ConfigDefault;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventBus;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.socketd.ToSocketdWebSocketListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 封于修
 */

@Slf4j
@ServerEndpoint("/config")
public class ClientConfigSocketdListener extends ToSocketdWebSocketListener {
    @Inject
    private ConfigService configService;

    @Inject("${config.maxSessions:1000}")
    private long maxSessions;

    public ClientConfigSocketdListener() {
        // clientMode=false，表示服务端模式
        super(new ConfigDefault(false));
    }

    @Init
    public void init() {
        setListener(buildListener());

        // 订阅本地配置变更事件
        EventBus.subscribe(ConfigChangeEvent.class, event -> {
            log.info("Received config change event: {}={} for {}/{}",
                    event.getKey(), event.getValue(), event.getProject(), event.getEnvironment());
            notifyConfigChange(event);
        });
    }

    public String getConfigByKey(String project, String environment, String key) {
        ConfigItem config = configService.getConfig(key, project, environment);
        if (config == null) {
            return "";
        }
        return config.getValue();
    }

    // 维护活跃的客户端会话 (按app和env分组)
    private final Map<String, Map<String, Session>> appEnvSessions = new ConcurrentHashMap<>();

    public void notifyConfigChange(ConfigChangeEvent event) {
        // 构建配置变更消息
        Map<String, Object> changeData = new HashMap<>();
        changeData.put(event.getKey(), event.getValue());
        String changeJson = ONode.serialize(changeData);
        // 生成app-env组合键
        String appEnvKey = event.getProject() + ":" + event.getEnvironment();

        // 只推送给匹配的app和env的客户端
        Map<String, Session> targetSessions = appEnvSessions.get(appEnvKey);
        if (targetSessions != null) {
            Set<String> pushedIps = new HashSet<>();
            // 使用副本遍历以避免并发修改异常
            Map<String, Session> sessionsCopy = new HashMap<>(targetSessions);
            sessionsCopy.forEach((sessionId, session) -> {
                try {
                    if (session.isValid()) {
                        // 获取客户端IP地址
                        String clientIp = getClientIp(session);
                        // 检查是否已经给这个IP推送过
                        if (clientIp != null && !pushedIps.contains(clientIp)) {
                            session.send("/push", new StringEntity(changeJson));
                            log.debug("Config change pushed to client {} for {}/{}",
                                    sessionId, event.getProject(), event.getEnvironment());
                            pushedIps.add(clientIp);
                        }
                    } else {
                        // 移除无效的会话
                        targetSessions.remove(sessionId);
                        // 如果该分组为空，清理空分组
                        if (targetSessions.isEmpty()) {
                            appEnvSessions.remove(appEnvKey);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to push config change to client: {}", sessionId, e);
                    targetSessions.remove(sessionId);
                    // 如果该分组为空，清理空分组
                    if (targetSessions.isEmpty()) {
                        appEnvSessions.remove(appEnvKey);
                    }
                }
            });
        }
    }


    private Listener buildListener() {
        return new EventListener()
                .doOnOpen(session -> {
                    log.info("Client connected: {}", session.sessionId());
                    // 从路径中解析app和env参数
                    String appNames = session.param("appNames");
                    String env = session.param("env");

                    if (appNames != null && env != null) {
                        // 处理多个应用名称的情况（如"user_social_demo"）
                        String[] apps = appNames.split("_");
                        for (String app : apps) {
                            String appEnvKey = app + ":" + env;
                            // 注册会话到对应的app-env分组（确保不会覆盖）
                            appEnvSessions.computeIfAbsent(appEnvKey, k -> new ConcurrentHashMap<>())
                                    .put(session.sessionId(), session);
                        }
                    } else {
                        log.warn("Client connected without app and env parameters");
                    }
                })
                .doOn("/batch_all", (s, m) -> {
                    Entity entity = m.entity();
                    String appsStr = entity.meta("apps");
                    String env = entity.meta("env");

                    if (appsStr == null || appsStr.isEmpty()) {
                        s.reply(m, new StringEntity("{}"));
                        return;
                    }

                    List<String> apps = Arrays.asList(appsStr.split(","));
                    Map<String, String> map = configService.findByProjectsAndEnvironment(apps, env);
                    String data = ONode.serialize(map);
                    long totalSessions = appEnvSessions.values().stream()
                            .mapToLong(Map::size)
                            .sum() / appEnvSessions.size();
                    log.info("Total sessions: {}", totalSessions);
                    if (m.isRequest()) {
                        s.reply(m, new StringEntity(data));
                    }
                })
                .doOn("/batch_keys", (s, m) -> {
                    Entity entity = m.entity();
                    String env = entity.meta("env");

                    if (env == null || env.isEmpty()) {
                        s.reply(m, new StringEntity("{}"));
                        return;
                    }

                    try {
                        // 解析请求体中的keys集合
                        String requestBody = entity.dataAsString();
                        Set<String> keys = parseKeysFromRequest(requestBody);

                        if (keys.isEmpty()) {
                            s.reply(m, new StringEntity("{}"));
                            return;
                        }

                        // 批量查询配置
                        Map<String, String> configs = configService.getBatchConfigsByKeys(keys, env);
                        String data = ONode.serialize(configs);

                        log.debug("Batch keys API returned {} configs for env: {}", configs.size(), env);
                        if (m.isRequest()) {
                            s.reply(m, new StringEntity(data));
                        }
                    } catch (Exception e) {
                        log.error("Failed to process batch_keys request", e);
                        s.reply(m, new StringEntity("{}"));
                    }
                })
                .doOn("/get", (s, m) -> {
                    Entity entity = m.entity();
                    String app = entity.meta("app");
                    String env = entity.meta("env");
                    String key = entity.meta("key");

                    String value = getConfigByKey(app, env, key);
                    if (m.isRequest()) {
                        s.reply(m, new StringEntity(value));
                    }
                })
                .doOn("/changes", (s, m) -> {
                    // 客户端请求获取最近的配置变更
                    Entity entity = m.entity();
                    String app = entity.meta("app");
                    String env = entity.meta("env");
                    Map<String, String> changesSince = configService.findChangesSince(app, env, new Date(System.currentTimeMillis() - 60 * 60 * 1000));
                    if (m.isRequest()) {
                        s.reply(m, new StringEntity(ONode.serialize(changesSince)));
                    }
                })
                .doOn("/ping", (s, m) -> {
                    // 处理健康检查ping请求
                    log.debug("Received ping from client: {}", s.sessionId());
                    if (m.isRequest()) {
                        // 计算当前维护的会话总数
                        long totalSessions = appEnvSessions.values().stream()
                                .mapToLong(Map::size)
                                .sum() / appEnvSessions.size();
                        s.reply(m, new StringEntity("{" +
                                "\"status\":\"ok\"," +
                                "\"total_sessions\":" + totalSessions + "," +
                                "\"max_sessions\":" + maxSessions +
                                "}"));
                    }
                })
                .doOnClose(s -> {
                    log.info("Client disconnected: {}", s.sessionId());
                    // 从所有app-env分组中移除断开连接的会话
                    appEnvSessions.forEach((appEnvKey, sessions) -> {
                        sessions.remove(s.sessionId());
                        // 如果该分组为空，清理空分组
                        if (sessions.isEmpty()) {
                            appEnvSessions.remove(appEnvKey);
                        }
                    });
                })
                .doOnError((s, err) -> {
                    log.error("Session error: {}, {}", s.sessionId(), err.getMessage(), err);
                    // 从所有app-env分组中移除错误的会话
                    appEnvSessions.forEach((appEnvKey, sessions) -> {
                        sessions.remove(s.sessionId());
                        if (sessions.isEmpty()) {
                            appEnvSessions.remove(appEnvKey);
                        }
                    });
                });
    }

    /**
     * 获取客户端IP地址
     * 优先从Socket.D协议的X-IP元信息中获取，如果没有则从远程地址解析
     */
    /**
     * 解析请求中的keys集合
     */
    private Set<String> parseKeysFromRequest(String requestBody) {
        try {
            if (requestBody == null || requestBody.trim().isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> keys = ONode.deserialize(requestBody, new TypeRef<Set<String>>() {
            });
            if (keys != null && !keys.isEmpty()) {
                return keys;
            }
            return Collections.emptySet();
        } catch (Exception e) {
            log.warn("Failed to parse keys from request body: {}", requestBody, e);
            return Collections.emptySet();
        }
    }

    private String getClientIp(Session session) {
        try {
            // 优先从协议元信息中获取X-Real-IP
            String realIp = session.attrOrDefault("X-IP", "");
            if (realIp != null && !realIp.trim().isEmpty()) {
                return realIp.trim();
            }
            // 最后从Session的远程地址解析IP
            String remoteAddress = session.remoteAddress().toString();
            if (remoteAddress.contains("/")) {
                return remoteAddress.split("/")[1].split(":")[0];
            }
            return remoteAddress;
        } catch (Exception e) {
            log.warn("Failed to get client IP for session: {}", session.sessionId(), e);
            return null;
        }
    }
}