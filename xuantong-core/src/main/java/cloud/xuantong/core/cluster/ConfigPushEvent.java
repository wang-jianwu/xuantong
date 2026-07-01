package cloud.xuantong.core.cluster;

import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import lombok.Getter;

/**
 * 配置推送事件
 * <p>
 * 由 Controller 发布，ConfigClusterBroadcaster 订阅处理。
 * 解耦 Controller 与推送实现。
 */
@Getter
public class ConfigPushEvent {
    private final ConfigChangeEvent configEvent;
    private final PushMode pushMode;
    private final String targetIp;
    private final double percentage;

    public ConfigPushEvent(ConfigChangeEvent configEvent, PushMode pushMode,
                           String targetIp, double percentage) {
        this.configEvent = configEvent;
        this.pushMode = pushMode;
        this.targetIp = targetIp;
        this.percentage = percentage;
    }

    public String getKey() { return configEvent.getKey(); }
    public String getValue() { return configEvent.getValue(); }
    public String getProject() { return configEvent.getProject(); }
    public String getEnvironment() { return configEvent.getEnvironment(); }
}
