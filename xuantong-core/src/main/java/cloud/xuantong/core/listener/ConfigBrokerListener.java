package cloud.xuantong.core.listener;

import cloud.xuantong.core.listener.model.PlayerInfo;
import cloud.xuantong.core.listener.model.PushLog;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.service.ConfigService;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 配置中心 Broker 监听器
 * <p>
 * 继承 BrokerListener，自动处理 Player 注册（@=name）、消息路由（.at()）
 * 在此基础上添加配置查询、推送逻辑和连接追踪
 * <p>
 * 推送策略逻辑委托给内部类 {@link PushStrategyDispatcher}，
 * 本类只负责消息路由和连接管理。
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
    private final ReentrantLock pushLogLock = new ReentrantLock();
    private static final int MAX_PUSH_LOGS = 100;

    /** 推送策略调度器 */
    private final PushStrategyDispatcher pushDispatcher = new PushStrategyDispatcher();

    // ===== 生命周期 =====

    @Override
    public void onOpen(Session session) throws IOException {
        // 先注册到 activePlayers，再调用 super.onOpen
        // 防止 super.onOpen 触发 onClose 时 activePlayers.remove 找不到条目
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
        super.onOpen(session);

        // token 鉴权（如果配置了 secretKey）
        if (secretKey != null && !secretKey.isEmpty()) {
            String token = session.param("token");
            if (!secretKey.equals(token)) {
                log.warn("Player auth failed: name={}, token={}, ip={}",
                        session.name(), token, getClientIp(session));
                activePlayers.remove(session.sessionId());
                session.close();
                return;
            }
        }

        log.info("Player connected: name={}, apps={}, sessionId={}, ip={}",
                session.name(), appsParam, session.sessionId(), info.getClientIp());
    }

    @Override
    public void onClose(Session session) {
        super.onClose(session);
        activePlayers.remove(session.sessionId());
        log.info("Player disconnected: name={}, sessionId={}", session.name(), session.sessionId());
    }

    // ===== 消息处理（只负责路由，不处理推送策略） =====

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

    // ===== 推送（ConfigPusher 接口实现，委托给 PushStrategyDispatcher） =====

    @Override
    public void pushConfigChange(String project, String env, String changeJson) {
        pushConfigChange(project, env, changeJson, false);
    }

    @Override
    public void pushConfigChange(String project, String env, String changeJson, boolean gray) {
        pushConfigChange(project, env, changeJson, gray, null, 0);
    }

    @Override
    public void pushConfigChange(String project, String env, String changeJson, boolean gray, String targetIp, double percentage) {
        try {
            PushLog pushLog = buildPushLog(project, env, changeJson);
            pushDispatcher.dispatch(pushLog, project, env, changeJson, gray, targetIp, percentage);
            recordPushLog(pushLog);
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
            replyIfRequest(session, message, new StringEntity("{}"));
            return;
        }

        List<String> apps = Arrays.asList(appsStr.split(","));
        Map<String, String> map = configService.findByProjectsAndEnvironment(apps, env);
        String data = ONode.serialize(map);

        replyIfRequest(session, message, new StringEntity(data));
    }

    private void handleBatchKeys(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String env = entity.meta("env");

        if (env == null || env.isEmpty()) {
            replyIfRequest(session, message, new StringEntity("{}"));
            return;
        }

        try {
            String requestBody = entity.dataAsString();
            Set<String> keys = parseKeysFromRequest(requestBody);
            if (keys.isEmpty()) {
                replyIfRequest(session, message, new StringEntity("{}"));
                return;
            }

            Map<String, String> configs = configService.getBatchConfigsByKeys(keys, env);
            replyIfRequest(session, message, new StringEntity(ONode.serialize(configs)));
        } catch (Exception e) {
            log.error("Failed to process batch_keys request", e);
            replyIfRequest(session, message, new StringEntity("{}"));
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
        if (config != null) {
            // 记录存在：value 可能是正常值、空串 ""、或 DB NULL（用空串传输）
            String value = config.getValue() != null ? config.getValue() : "";
            replyIfRequest(session, message, new StringEntity(value).metaPut("found", "true"));
        } else {
            // 记录不存在：未配置
            replyIfRequest(session, message, new StringEntity("").metaPut("found", "false"));
        }
    }

    private void handlePing(Session session, Message message) throws IOException {
        if (message.isRequest() && session != null) {
            // 使用 ONode 构建 JSON，避免手动拼接字符串的注入风险
            ONode response = new ONode()
                    .set("status", "ok")
                    .set("total_sessions", getNameAll().size());
            session.reply(message, new StringEntity(response.toJson()));
        }
    }

    // ===== 推送日志 =====

    private PushLog buildPushLog(String project, String env, String changeJson) {
        PushLog pushLog = new PushLog();
        pushLog.setProject(project);
        pushLog.setEnv(env);
        pushLog.setTimestamp(System.currentTimeMillis());
        try {
            Map<String, Object> changeMap = ONode.deserialize(changeJson, new TypeRef<>() {
            });
            if (changeMap != null && !changeMap.isEmpty()) {
                pushLog.setChangeKey(changeMap.keySet().iterator().next());
            }
        } catch (Exception e) {
            log.warn("Failed to parse changeJson for push log", e);
        }
        return pushLog;
    }

    /**
     * 记录推送日志，使用 ReentrantLock 保护，超限批量裁剪（避免 CopyOnWriteArrayList 逐条 remove 的 O(k*n) 开销）
     */
    private void recordPushLog(PushLog pushLog) {
        pushLogs.add(0, pushLog);
        if (pushLogs.size() > MAX_PUSH_LOGS) {
            pushLogLock.lock();
            try {
                int excess = pushLogs.size() - MAX_PUSH_LOGS;
                if (excess > 0) {
                    pushLogs.subList(pushLogs.size() - excess, pushLogs.size()).clear();
                }
            } finally {
                pushLogLock.unlock();
            }
        }
    }

    // ===== 监控数据查询（BrokerMonitor 接口实现） =====

    @Override
    public List<PlayerInfo> getActivePlayers() {
        return new ArrayList<>(activePlayers.values());
    }

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

    @Override
    public int getActivePlayerCount() {
        return activePlayers.size();
    }

    // ===== 工具方法 =====

    /** 请求消息统一回复工具，避免重复判空嵌套 */
    private void replyIfRequest(Session session, Message message, Entity entity) throws IOException {
        if (message.isRequest() && session != null) {
            session.reply(message, entity);
        }
    }

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

    // ========================================================================
    /**
     * 推送策略调度器（内部类）
     * <p>
     * 封装四种推送策略：按 IP、按比例、灰度（单播随机 1 台）、全量（组播）。
     * 与消息路由逻辑分离，本类仅关注"向哪些 session 发、怎么发"。
     */
    private class PushStrategyDispatcher {

        void dispatch(PushLog pushLog, String project, String env, String changeJson,
                      boolean gray, String targetIp, double percentage) throws IOException {
            if (targetIp != null && !targetIp.isEmpty()) {
                pushByIp(pushLog, env, targetIp, changeJson);
            } else if (percentage > 0 && percentage < 1) {
                pushByPercentage(pushLog, project, env, changeJson, percentage);
            } else if (gray) {
                pushGray(pushLog, env, changeJson);
            } else {
                pushFull(pushLog, env, changeJson);
            }
        }

        /** 按 IP 推送：直接找到匹配的 session 发送 */
        private void pushByIp(PushLog pushLog, String env, String targetIp, String changeJson) throws IOException {
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
            log.info("IP push: env={}, targetIp={}, count={}", pushLog.getEnv(), targetIp, count);
        }

        /** 按比例推送：随机选 N 台 */
        private void pushByPercentage(PushLog pushLog, String project, String env, String changeJson, double percentage) throws IOException {
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
        }

        /** 灰度推送：单播随机选 1 台 */
        private void pushGray(PushLog pushLog, String env, String changeJson) throws IOException {
            // 先检查是否有在线 Player，避免日志硬编码为 1 但实际无人收到
            boolean hasPlayer = activePlayers.values().stream()
                    .anyMatch(p -> env.equals(p.getPlayerName()));
            pushLog.setTargetPlayerCount(hasPlayer ? 1 : 0);
            broadcast("/config-change", new StringEntity(changeJson).at(env));
            log.info("Gray push: project={}, env={}, key={}, targetPlayers={}",
                    pushLog.getProject(), env, pushLog.getChangeKey(), pushLog.getTargetPlayerCount());
        }

        /** 全量推送：组播所有匹配的 Player */
        private void pushFull(PushLog pushLog, String env, String changeJson) throws IOException {
            int targetCount = 0;
            for (PlayerInfo info : activePlayers.values()) {
                if (env.equals(info.getPlayerName())) {
                    targetCount++;
                }
            }
            pushLog.setTargetPlayerCount(targetCount);
            broadcast("/config-change", new StringEntity(changeJson).at(env + "*"));
            log.info("Full push: project={}, env={}, key={}, targetPlayers={}",
                    pushLog.getProject(), env, pushLog.getChangeKey(), targetCount);
        }
    }
}
