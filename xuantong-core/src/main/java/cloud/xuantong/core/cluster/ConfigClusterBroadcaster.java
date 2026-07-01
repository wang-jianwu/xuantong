package cloud.xuantong.core.cluster;

import cloud.xuantong.core.listener.ConfigBrokerListener;
import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置变更广播器（Broker 模式）
 * <p>
 * 职责：
 * 1. 客户端推送：通过 Broker 组播，推送给匹配的客户端 Player（@=project:env*）
 * 2. 集群同步：通过 Broker 组播，广播给其他配置中心节点（@=config-node*）
 * <p>
 * 配置变更传播路径：
 * API → ConfigService → ConfigClusterBroadcaster
 * → brokerListener.pushConfigChange()（客户端推送）
 * → brokerListener.broadcastClusterSync()（集群同步）
 */
@Slf4j
@Component
public class ConfigClusterBroadcaster {

    @Inject
    private ConfigBrokerListener brokerListener;

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
        Map<String, Object> changeData = new HashMap<>();
        changeData.put(event.getKey(), event.getValue());
        String changeJson = ONode.serialize(changeData);

        // 推送给客户端 Player
        brokerListener.pushConfigChange(event.getProject(), event.getEnvironment(), changeJson, gray);

        // 全量推送时才集群同步（灰度只推本地1台，不同步其他节点）
        if (!gray) {
            String syncJson = ONode.serialize(event);
            brokerListener.broadcastClusterSync(syncJson);
        }

        log.debug("Config change broadcast: {}={} gray={}", event.getKey(), event.getValue(), gray);
    }

    /**
     * 处理来自集群的配置变更
     * 由 ClusterSyncPlayer 收到 /cluster-sync 消息后调用
     *
     * @param event 配置变更事件
     */
    public void handleClusterSync(ConfigChangeEvent event) {
        log.debug("Handling cluster sync: {}={}", event.getKey(), event.getValue());

        // 推送给本地连接的客户端 Player
        Map<String, Object> changeData = new HashMap<>();
        changeData.put(event.getKey(), event.getValue());
        String changeJson = ONode.serialize(changeData);

        brokerListener.pushConfigChange(event.getProject(), event.getEnvironment(), changeJson);
    }
}
