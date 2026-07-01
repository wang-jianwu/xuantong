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
import java.util.stream.Collectors;
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
public class ConfigBrokerListener extends BrokerListener implements ConfigPusher, BrokerMonitor {

    @Inject
    private ConfigService configService;

    @Inject("${config.broker.secretKey:}")
    private String secretKey;

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

        // token 鉴权（如果配置了 secretKey）
        if (secretKey != null && !secretKey.isEmpty()) {
            String token = session.param("token");
            if (!secretKey.equals(token)) {
                log.warn("Player auth failed: name={}, token={}, ip={}",
                        session.name(), token, getClientIp(session));
                session.close();
                return;
            }
        }

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

    // ===== 推送（ConfigPusher 接口实现） =====

    /**
     * 全量推送：推给同环境的所有 Player
     */
    @Override
    public void pushConfigChange(String project, String env, String changeJson) {
        pushConfigChange(project, env, changeJson, false);
    }

    /**
     * 推送配置变更
     * @param gray true=灰度推送（单播1台），false=全量推送（组播所有）
     */
    @Override
    public void pushConfigChange(String project, String env, String changeJson, boolean gray) {
        pushConfigChange(project, env, changeJson, gray, null, 0);
    }

    /**
     * 推送配置变更（支持 IP 指定和比例灰度）
     * @param gray       true=灰度推送
     * @param targetIp   指定目标 IP（不为 null 时按 IP 推）
     * @param percentage 按比例推送（0~1，如 0.1 表示 10%）
     */
    @Override
    public void pushConfigChange(String project, String env, String changeJson, boolean gray, String targetIp, double percentage) {
        try {
            PushLog pushLog = new PushLog();
            pushLog.setProject(project);
            pushLog.setEnv(env);
            pushLog.setTimestamp(System.currentTimeMillis());
            try {
                Map<String, Object> changeMap = ONode.deserialize(changeJson, new TypeRef<Map<String, Object>>() {});
                if (changeMap != null && !changeMap.isEmpty()) {
                    pushLog.setChangeKey(changeMap.keySet().iterator().next());
                }
            } catch (Exception e) {
                log.warn("Failed to parse changeJson for push log", e);
            }

            // 按 IP 推送：直接找到匹配的 session 发送
            if (targetIp != null && !targetIp.isEmpty()) {
                int count = 0;
                for (PlayerInfo info : activePlayers.values()) {
                    if (targetIp.equals(info.getClientIp()) && env.equals(info.getPlayerName())) {
                        Session session = getSessionById(info.getSessionId());
                        if (session != null && session.isValid()) {
                            session.send("/config-change", new StringEntity(changeJson));
                            count++;
                        }
                    }
                }
                pushLog.setTargetPlayerCount(count);
                log.info("IP push: project={}, env={}, targetIp={}, count={}", project, env, targetIp, count);

            // 按比例推送：随机选 N 台
            } else if (percentage > 0 && percentage < 1) {
                List<PlayerInfo> candidates = activePlayers.values().stream()
                        .filter(p -> env.equals(p.getPlayerName()))
                        .collect(Collectors.toList());
                Collections.shuffle(candidates);
                int count = (int) Math.ceil(candidates.size() * percentage);
                int sent = 0;
                for (int i = 0; i < count && i < candidates.size(); i++) {
                    PlayerInfo info = candidates.get(i);
                    Session session = getSessionById(info.getSessionId());
                    if (session != null && session.isValid()) {
                        session.send("/config-change", new StringEntity(changeJson));
                        sent++;
                    }
                }
                pushLog.setTargetPlayerCount(sent);
                log.info("Percentage push: project={}, env={}, {}% target={} sent={}",
                        project, env, (int)(percentage * 100), count, sent);

            } else if (gray) {
                // 现有灰度：单播随机选 1 台
                broadcast("/config-change", new StringEntity(changeJson).at(env));
                pushLog.setTargetPlayerCount(1);
                log.info("Gray push: project={}, env={}, key={}", project, env, pushLog.getChangeKey());
            } else {
                // 全量推送：组播所有
                int targetCount = 0;
                for (PlayerInfo info : activePlayers.values()) {
                    if (env.equals(info.getPlayerName())) {
                        targetCount++;
                    }
                }
                pushLog.setTargetPlayerCount(targetCount);
                broadcast("/config-change", new StringEntity(changeJson).at(env + "*"));
                log.info("Full push: project={}, env={}, key={}, targetPlayers={}",
                        project, env, pushLog.getChangeKey(), targetCount);
            }

            pushLogs.add(0, pushLog);
            while (pushLogs.size() > MAX_PUSH_LOGS) {
                pushLogs.remove(pushLogs.size() - 1);
            }

        } catch (Exception e) {
            log.debug("Push failed: env={} - {}", env, e.getMessage());
        }
    }

    @Override
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

    // ===== 监控数据查询（BrokerMonitor 接口实现） =====

    /**
     * 获取所有活跃 Player 信息
     */
    @Override
    public List<PlayerInfo> getActivePlayers() {
        return new ArrayList<>(activePlayers.values());
    }

    /**
     * 获取推送日志
     */
    @Override
    public List<PushLog> getPushLogs() {
        return new ArrayList<>(pushLogs);
    }

    /**
     * 检查是否有客户端订阅了指定项目
     * 注意：subscribedApps 只在客户端发起请求后才被填充，刚连上的客户端可能检测不到
     */
    @Override
    public boolean hasSubscriber(String project, String env) {
        for (PlayerInfo info : activePlayers.values()) {
            if (env.equals(info.getPlayerName()) && info.getSubscribedApps().contains(project)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取活跃 Player 数量
     */
    @Override
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
