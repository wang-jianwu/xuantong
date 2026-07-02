package cloud.xuantong.core.cluster;

import cloud.xuantong.core.listener.model.ConfigChangeEvent;

/**
 * 配置推送事件
 * <p>
 * 由 Controller 发布，ConfigClusterBroadcaster 订阅处理。
 * 解耦 Controller 与推送实现。
 */
public record ConfigPushEvent(ConfigChangeEvent configEvent, PushMode pushMode, String targetIp, double percentage) {

    public String getKey() {
        return configEvent.getKey();
    }

    public String getValue() {
        return configEvent.getValue();
    }

    public String getProject() {
        return configEvent.getProject();
    }

    public String getEnvironment() {
        return configEvent.getEnvironment();
    }
}
