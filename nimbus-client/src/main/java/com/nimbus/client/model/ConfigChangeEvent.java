package com.nimbus.client.model;

/**
 * 配置变更事件
 */
public class ConfigChangeEvent {
    private final String key;
    private final String value;

    public ConfigChangeEvent(String key, String value) {
        this.key = key;
        this.value = value;
    }

    // Getters
    public String getKey() {
        return key;
    }

    public String getNewValue() {
        return value;
    }

    @Override
    public String toString() {
        return "ConfigChangeEvent{" +
                "key='" + key + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}