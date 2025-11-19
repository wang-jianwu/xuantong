package com.nimbus.core.listener;

import com.nimbus.core.listener.model.ConfigChangeEvent;
import com.nimbus.core.model.ConfigItem;
import com.nimbus.core.repository.ConfigLogRepository;
import com.nimbus.core.repository.ConfigRepository;
import com.nimbus.core.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.EntityMetas;
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
 * @author wangjianwu
 */

@Slf4j
@ServerEndpoint("/config")
public class ClientConfigSocketdListener extends ToSocketdWebSocketListener {
    @Inject
    private ConfigService configService;

    public ClientConfigSocketdListener() {
        // clientMode=false，表示服务端模式
        super(new ConfigDefault(false));
    }

    @Init
    public void init() {
        setListener(buildListener());

        // 订阅配置变更事件
        EventBus.subscribe(ConfigChangeEvent.class, event -> {
            log.info("Received config change event: {}={} for {}/{}",
                    event.getKey(), event.getValue(), event.getProject(), event.getEnvironment());
            notifyConfigChange(event);
        });
    }

    public Map<String, String> getAllConfigsAsMap(String project, String environment) {
        return configService.findByProjectAndEnvironment(project, environment);
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
            // 使用集合记录已经推送过的客户端IP，避免同一机器重复推送
            Set<String> pushedIps = new HashSet<>();

            targetSessions.forEach((sessionId, session) -> {
                try {
                    if (session.isValid()) {
                        // 获取客户端IP地址
                        String clientIp = getClientIp(session);

                        // 检查是否已经给这个IP推送过
                        if (clientIp != null && !pushedIps.contains(clientIp)) {
                            session.send("/push", new StringEntity(changeJson));
                            pushedIps.add(clientIp);
                            log.debug("Config change pushed to client {} (IP: {}) for {}/{}",
                                    sessionId, clientIp, event.getProject(), event.getEnvironment());
                        } else {
                            log.debug("Skipping duplicate push for IP: {}", clientIp);
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

    /**
     * 获取客户端IP地址
     * 优先从Socket.D协议的X-IP元信息中获取，如果没有则从远程地址解析
     */
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

    private Listener buildListener() {
        return new EventListener()
                .doOnOpen(session -> {
                    log.info("Client connected: {}", session.sessionId());
                    String path = session.path();

                    // 从路径中解析app和env参数
                    String app = session.param("app");
                    String env = session.param("env");

                    if (app != null && env != null) {
                        String appEnvKey = app + ":" + env;
                        // 注册会话到对应的app-env分组
                        appEnvSessions.computeIfAbsent(appEnvKey, k -> new ConcurrentHashMap<>())
                                .put(session.sessionId(), session);
                        log.info("Session registered for app={}, env={}", app, env);
                    } else {
                        log.warn("Client connected without app and env parameters: {}", path);
                    }
                })
                .doOn("/all", (s, m) -> {
                    Entity entity = m.entity();
                    String app = entity.meta("app");
                    String env = entity.meta("env");
                    Map<String, String> map = getAllConfigsAsMap(app, env);
                    String data = ONode.serialize(map);

                    if (m.isRequest()) {
                        s.reply(m, new StringEntity(data));
                    }

                    if (m.isSubscribe()) {
                        int size = m.metaAsInt(EntityMetas.META_RANGE_SIZE);
                        // 订阅配置变更 todo

                        s.replyEnd(m, new StringEntity("ok"));
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
                        s.reply(m, new StringEntity("{\"status\":\"ok\",\"timestamp\":" + System.currentTimeMillis() + "}"));
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
}