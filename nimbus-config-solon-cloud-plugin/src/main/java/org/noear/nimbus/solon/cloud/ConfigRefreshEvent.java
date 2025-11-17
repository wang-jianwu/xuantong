package org.noear.nimbus.solon.cloud;

/**
 * 配置刷新事件
 */
public class ConfigRefreshEvent {
    private final String key;

    public ConfigRefreshEvent(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}