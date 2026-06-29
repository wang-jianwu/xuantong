package cloud.xuantong.core.listener;

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
import java.util.*;

/**
 * 配置中心 Broker 监听器
 * <p>
 * 继承 BrokerListener，自动处理 Player 注册（@=name）、消息路由（.at()）
 * 在此基础上添加配置查询和推送逻辑
 * <p>
 * 支持的消息模式：
 * - 单播：.at("name") → 轮询负载均衡
 * - 单播!：.at("name!") → ip_hash 负载均衡
 * - 组播：.at("name*") → 同名所有 Player
 * - 广播：.at("*") → 全部 Player
 */
@Slf4j
@Component
public class ConfigBrokerListener extends BrokerListener {

    @Inject
    private ConfigService configService;

    /**
     * 消息处理入口
     * 配置查询类请求自己处理，其他消息走 Broker 默认转发
     */
    @Override
    public void onMessage(Session requester, Message message) throws IOException {
        String event = message.event();

        // 配置查询类请求，自己处理（不转发）
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

        // 其他消息走 Broker 默认转发逻辑
        super.onMessage(requester, message);
    }

    /**
     * 推送配置变更给匹配的客户端 Player
     * 使用组播模式：推送给所有 @=project:env 的 Player
     *
     * @param project    项目名
     * @param env        环境
     * @param changeJson 变更 JSON
     */
    public void pushConfigChange(String project, String env, String changeJson) {
        try {
            String target = project + ":" + env;
            broadcast("/config-change", new StringEntity(changeJson).at(target + "*"));
            log.debug("Config change pushed to players: {}", target);
        } catch (Exception e) {
            log.error("Failed to push config change to players", e);
        }
    }

    /**
     * 集群同步：广播给其他配置中心节点
     *
     * @param syncJson 同步 JSON
     */
    public void broadcastClusterSync(String syncJson) {
        try {
            broadcast("/cluster-sync", new StringEntity(syncJson).at("config-node*"));
            log.debug("Cluster sync broadcast to config-node players");
        } catch (Exception e) {
            log.error("Failed to broadcast cluster sync", e);
        }
    }

    // ===== 请求处理器 =====

    private void handleBatchAll(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String appsStr = entity.meta("apps");
        String env = entity.meta("env");

        if (appsStr == null || appsStr.isEmpty()) {
            if (message.isRequest()) {
                session.reply(message, new StringEntity("{}"));
            }
            return;
        }

        List<String> apps = Arrays.asList(appsStr.split(","));
        Map<String, String> map = configService.findByProjectsAndEnvironment(apps, env);
        String data = ONode.serialize(map);

        log.debug("Batch all request: apps={}, env={}, resultSize={}", apps, env, map.size());

        if (message.isRequest()) {
            session.reply(message, new StringEntity(data));
        }
    }

    private void handleBatchKeys(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String env = entity.meta("env");

        if (env == null || env.isEmpty()) {
            if (message.isRequest()) {
                session.reply(message, new StringEntity("{}"));
            }
            return;
        }

        try {
            String requestBody = entity.dataAsString();
            Set<String> keys = parseKeysFromRequest(requestBody);

            if (keys.isEmpty()) {
                if (message.isRequest()) {
                    session.reply(message, new StringEntity("{}"));
                }
                return;
            }

            Map<String, String> configs = configService.getBatchConfigsByKeys(keys, env);
            log.debug("Batch keys request: keys.size={}, env={}, resultSize={}", keys.size(), env, configs.size());

            if (message.isRequest()) {
                session.reply(message, new StringEntity(ONode.serialize(configs)));
            }
        } catch (Exception e) {
            log.error("Failed to process batch_keys request", e);
            if (message.isRequest()) {
                session.reply(message, new StringEntity("{}"));
            }
        }
    }

    private void handleGet(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String app = entity.meta("app");
        String env = entity.meta("env");
        String key = entity.meta("key");

        ConfigItem config = configService.getConfig(key, env, app);
        String value = (config != null) ? config.getValue() : "";

        log.debug("Get config: app={}, env={}, key={}, hasValue={}", app, env, key, config != null);

        if (message.isRequest()) {
            session.reply(message, new StringEntity(value));
        }
    }

    private void handlePing(Session session, Message message) throws IOException {
        if (message.isRequest()) {
            // 获取当前 Player 总数
            int playerCount = getNameAll().size();
            session.reply(message, new StringEntity(
                    "{\"status\":\"ok\",\"total_sessions\":" + playerCount + "}"));
            log.debug("Ping response: playerCount={}", playerCount);
        }
    }

    /**
     * 解析请求中的 keys 集合
     */
    private Set<String> parseKeysFromRequest(String requestBody) {
        try {
            if (requestBody == null || requestBody.trim().isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> keys = ONode.deserialize(requestBody, new TypeRef<Set<String>>() {});
            if (keys != null && !keys.isEmpty()) {
                return keys;
            }
            return Collections.emptySet();
        } catch (Exception e) {
            log.warn("Failed to parse keys from request body: {}", requestBody, e);
            return Collections.emptySet();
        }
    }
}
