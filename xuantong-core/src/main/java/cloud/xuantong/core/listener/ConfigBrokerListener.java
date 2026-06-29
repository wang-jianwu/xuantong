package cloud.xuantong.core.listener;

import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.service.ConfigService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.socketd.broker.BrokerListener;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置中心 Broker 监听器
 * <p>
 * 继承 BrokerListener，自动处理 Player 注册（@=name）、消息路由（.at()）
 * 在此基础上添加配置查询、推送逻辑和连接追踪
 */
@Slf4j
@Component
public class ConfigBrokerListener extends BrokerListener {

    @Inject
    private ConfigService configService;

    // ===== 连接追踪 =====

    /** 活跃会话: sessionId → PlayerInfo */
    private final Map<String, PlayerInfo> activePlayers = new ConcurrentHashMap<>();

    /** 推送日志（最近 100 条） */
    private final List<PushLog> pushLogs = new CopyOnWriteArrayList<>();
    private static final int MAX_PUSH_LOGS = 100;

    @Data
    public static class PlayerInfo {
        private String sessionId;
        private String playerName;      // @=name 中的 name
        private String clientIp;
        private long connectedTime;
        private Set<String> subscribedApps = ConcurrentHashMap.newKeySet();  // 订阅的项目
        private long lastRequestTime;
    }

    @Data
    public static class PushLog {
        private String project;
        private String env;
        private String changeKey;
        private int targetPlayerCount;
        private long timestamp;
    }

    // ===== 生命周期 =====

    @Override
    public void onOpen(Session session) throws IOException {
        super.onOpen(session);

        PlayerInfo info = new PlayerInfo();
        info.setSessionId(session.sessionId());
        info.setPlayerName(session.name());
        info.setClientIp(getClientIp(session));
        info.setConnectedTime(System.currentTimeMillis());
        info.setLastRequestTime(System.currentTimeMillis());

        // 从连接参数获取订阅的项目列表
        String appsParam = session.param("apps");
        if (appsParam != null && !appsParam.isEmpty()) {
            info.getSubscribedApps().addAll(Arrays.asList(appsParam.split(",")));
        }

        activePlayers.put(session.sessionId(), info);
        log.info("Player connected: name={}, apps={}, sessionId={}, ip={}",
                session.name(), appsParam, session.sessionId(), info.getClientIp());
    }

    @Override
    public void onClose(Session session) {
        super.onClose(session);
        activePlayers.remove(session.sessionId());
        log.info("Player disconnected: name={}, sessionId={}", session.name(), session.sessionId());
    }

    // ===== 消息处理 =====

    @Override
    public void onMessage(Session requester, Message message) throws IOException {
        String event = message.event();

        // 更新最后请求时间
        if (requester != null) {
            PlayerInfo info = activePlayers.get(requester.sessionId());
            if (info != null) {
                info.setLastRequestTime(System.currentTimeMillis());
            }
        }

        switch (event) {
            case "/batch_all":
                handleBatchAll(requester, message);
                return;
            case "/batch_keys":
                handleBatchKeys(requester, message);
                return;
            case "/get":
                handleGet(requester, message);
                return;
            case "/ping":
                handlePing(requester, message);
                return;
        }

        super.onMessage(requester, message);
    }

    // ===== 推送 =====

    public void pushConfigChange(String project, String env, String changeJson) {
        try {
            // 记录推送日志
            PushLog pushLog = new PushLog();
            pushLog.setProject(project);
            pushLog.setEnv(env);
            pushLog.setTimestamp(System.currentTimeMillis());
            try {
                Map<String, Object> changeMap = ONode.deserialize(changeJson, new TypeRef<Map<String, Object>>() {});
                if (changeMap != null && !changeMap.isEmpty()) {
                    pushLog.setChangeKey(changeMap.keySet().iterator().next());
                }
            } catch (Exception ignored) {}

            // 统计目标 Player 数量
            int targetCount = 0;
            for (PlayerInfo info : activePlayers.values()) {
                if (env.equals(info.getPlayerName())) {
                    targetCount++;
                }
            }
            pushLog.setTargetPlayerCount(targetCount);

            // 添加到日志列表（保留最近 MAX_PUSH_LOGS 条）
            pushLogs.add(0, pushLog);
            while (pushLogs.size() > MAX_PUSH_LOGS) {
                pushLogs.remove(pushLogs.size() - 1);
            }

            // 广播给同环境的所有 Player
            broadcast("/config-change", new StringEntity(changeJson).at(env + "*"));
            log.info("Config change pushed: project={}, env={}, key={}, targetPlayers={}",
                    project, env, pushLog.getChangeKey(), targetCount);

        } catch (Exception e) {
            log.debug("No matching players for env: {} - {}", env, e.getMessage());
        }
    }

    public void broadcastClusterSync(String syncJson) {
        try {
            broadcast("/cluster-sync", new StringEntity(syncJson).at("config-node*"));
            log.debug("Cluster sync broadcast to config-node players");
        } catch (Exception e) {
            log.debug("No cluster nodes connected: {}", e.getMessage());
        }
    }

    // ===== 请求处理器 =====

    private void handleBatchAll(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String appsStr = entity.meta("apps");
        String env = entity.meta("env");

        // 记录订阅的项目
        if (session != null && appsStr != null) {
            PlayerInfo info = activePlayers.get(session.sessionId());
            if (info != null) {
                info.getSubscribedApps().addAll(Arrays.asList(appsStr.split(",")));
            }
        }

        if (appsStr == null || appsStr.isEmpty()) {
            if (message.isRequest()) if (session != null) {
                session.reply(message, new StringEntity("{}"));
            }
            return;
        }

        List<String> apps = Arrays.asList(appsStr.split(","));
        Map<String, String> map = configService.findByProjectsAndEnvironment(apps, env);
        String data = ONode.serialize(map);

        if (message.isRequest()) if (session != null) {
            session.reply(message, new StringEntity(data));
        }
    }

    private void handleBatchKeys(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String env = entity.meta("env");

        if (env == null || env.isEmpty()) {
            if (message.isRequest()) session.reply(message, new StringEntity("{}"));
            return;
        }

        try {
            String requestBody = entity.dataAsString();
            Set<String> keys = parseKeysFromRequest(requestBody);
            if (keys.isEmpty()) {
                if (message.isRequest()) session.reply(message, new StringEntity("{}"));
                return;
            }

            Map<String, String> configs = configService.getBatchConfigsByKeys(keys, env);
            if (message.isRequest()) session.reply(message, new StringEntity(ONode.serialize(configs)));
        } catch (Exception e) {
            log.error("Failed to process batch_keys request", e);
            if (message.isRequest()) session.reply(message, new StringEntity("{}"));
        }
    }

    private void handleGet(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String app = entity.meta("app");
        String env = entity.meta("env");
        String key = entity.meta("key");

        // 记录订阅的项目
        if (session != null && app != null) {
            PlayerInfo info = activePlayers.get(session.sessionId());
            if (info != null) {
                info.getSubscribedApps().add(app);
            }
        }

        ConfigItem config = configService.getConfig(key, env, app);
        String value = (config != null) ? config.getValue() : "";

        if (message.isRequest()) if (session != null) {
            session.reply(message, new StringEntity(value));
        }
    }

    private void handlePing(Session session, Message message) throws IOException {
        if (message.isRequest()) {
            int playerCount = getNameAll().size();
            session.reply(message, new StringEntity(
                    "{\"status\":\"ok\",\"total_sessions\":" + playerCount + "}"));
        }
    }

    // ===== 监控数据查询 =====

    /**
     * 获取所有活跃 Player 信息
     */
    public List<PlayerInfo> getActivePlayers() {
        return new ArrayList<>(activePlayers.values());
    }

    /**
     * 获取推送日志
     */
    public List<PushLog> getPushLogs() {
        return new ArrayList<>(pushLogs);
    }

    /**
     * 获取活跃 Player 数量
     */
    public int getActivePlayerCount() {
        return activePlayers.size();
    }

    // ===== 工具方法 =====

    private Set<String> parseKeysFromRequest(String requestBody) {
        try {
            if (requestBody == null || requestBody.trim().isEmpty()) return Collections.emptySet();
            Set<String> keys = ONode.deserialize(requestBody, new TypeRef<Set<String>>() {});
            return (keys != null && !keys.isEmpty()) ? keys : Collections.emptySet();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private String getClientIp(Session session) {
        try {
            String remote = session.remoteAddress().toString();
            if (remote.contains("/")) return remote.split("/")[1].split(":")[0];
            return remote;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
