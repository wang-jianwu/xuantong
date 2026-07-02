package cloud.xuantong.core.cluster;

import cloud.xuantong.core.listener.ConfigPusher;
import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventListener;

import java.util.Collections;

/**
 * 配置变更广播器（Broker 模式）
 * <p>
 * 职责：
 * 1. 客户端推送：通过 ConfigPusher 组播，推送给匹配的客户端 Player
 * 2. 集群同步：通过 ConfigPusher 组播，广播给其他配置中心节点
 * <p>
 * 配置变更传播路径：
 * API → EventBus → ConfigClusterBroadcaster → ConfigPusher（抽象）
 * → ConfigBrokerListener（Socket.D 实现）
 */
@Slf4j
@Component
public class ConfigClusterBroadcaster implements EventListener<ConfigPushEvent> {

    @Inject
    private ConfigPusher pusher;

    @Inject
    private ClusterConfig clusterConfig;

    /**
     * 事件总线订阅：处理 ConfigPushEvent
     */
    @Override
    public void onEvent(ConfigPushEvent pushEvent) throws Throwable {
        ConfigChangeEvent event = pushEvent.configEvent();
        switch (pushEvent.pushMode()) {
            case NONE:
                return;
            case ALL:
                broadcastConfigChange(event, false);
                break;
            case GRAY:
                broadcastConfigChange(event, true);
                break;
            case IP:
                broadcastConfigChange(event, true, pushEvent.targetIp(), 0);
                break;
            case PERCENTAGE:
                broadcastConfigChange(event, true, null, pushEvent.percentage());
                break;
        }
    }

    /**
     * 全量推送配置变更
     */
    public void broadcastConfigChange(ConfigChangeEvent event) {
        broadcastConfigChange(event, false);
    }

    /**
     * 推送配置变更
     * @param gray true=灰度推送（仅1台），false=全量推送
     */
    public void broadcastConfigChange(ConfigChangeEvent event, boolean gray) {
        broadcastConfigChange(event, gray, null, 0);
    }

    /**
     * 推送配置变更（支持 IP 指定和比例灰度）
     * @param gray       true=灰度推送
     * @param targetIp   指定目标 IP
     * @param percentage 按比例（0~1）
     */
    public void broadcastConfigChange(ConfigChangeEvent event, boolean gray, String targetIp, double percentage) {
        String changeJson = buildChangeJson(event);

        // 推送给客户端 Player
        pusher.pushConfigChange(event.getProject(), event.getEnvironment(), changeJson, gray, targetIp, percentage);

        // 全量推送时才集群同步
        if (!gray) {
            event.setSourceNodeId(clusterConfig.getNodeId());
            String syncJson = ONode.serialize(event, Options.of(Feature.Write_Nulls));
            pusher.broadcastClusterSync(syncJson);
        }

        log.debug("Config change broadcast: key={} encrypted={} gray={} ip={} pct={}",
                event.getKey(), event.getValue() != null && event.getValue().length() > 0, gray, targetIp, percentage);
    }

    /**
     * 处理来自集群的配置变更
     * 由 ClusterSyncPlayer 收到 /cluster-sync 消息后调用
     * <p>
     * 注意：只推送给本地客户端 Player，不再触发集群广播（防止无限循环）
     *
     * @param event 配置变更事件
     */
    public void handleClusterSync(ConfigChangeEvent event) {
        log.debug("Handling cluster sync: key={}", event.getKey());

        // 仅推送给本地连接的客户端 Player，不广播回集群
        pusher.pushConfigChange(event.getProject(), event.getEnvironment(), buildChangeJson(event));
    }

    /**
     * 构建单 key 变更 JSON
     * null {} [] 保留
     */
    private String buildChangeJson(ConfigChangeEvent event) {
        return ONode.serialize(Collections.singletonMap(event.getKey(), event.getValue()), Options.of(Feature.Write_Nulls));
    }
}
