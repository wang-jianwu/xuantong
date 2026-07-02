package cloud.xuantong.core.listener.model;

import lombok.Getter;
import lombok.Setter;

/**
 * author 封于修
 */
@Getter
public class ConfigChangeEvent {
    private final String key;
    private final String value;
    private final String project;
    private final String environment;

    /** 集群同步防环：标识事件最初产生的节点，接收方据此忽略自身产生的事件 */
    @Setter
    private String sourceNodeId;

    public ConfigChangeEvent(String key, String value, String project,
                             String environment) {
        this.key = key;
        this.value = value;
        this.project = project;
        this.environment = environment;
    }
}
