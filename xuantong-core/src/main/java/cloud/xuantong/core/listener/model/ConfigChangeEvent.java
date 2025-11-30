package cloud.xuantong.core.listener.model;

import lombok.Getter;

/**
 * author wangjianwu
 */
@Getter
public class ConfigChangeEvent {
    private final String key;
    private final String value;
    private final String project;
    private final String environment;

    public ConfigChangeEvent(String key, String value, String project,
                             String environment) {
        this.key = key;
        this.value = value;
        this.project = project;
        this.environment = environment;
    }
}
