package com.nimbus.client.model;

import java.util.Date;

/**
 * 配置变更事件
 */
public class ConfigChangeEvent {
    private final String key;
    private final String newValue;
    private final String oldValue;
    private final Date changeTime;
    private final String operator;

    public ConfigChangeEvent(String key, String newValue) {
        this(key, newValue, null, new Date(), "system");
    }

    public ConfigChangeEvent(String key, String newValue, String oldValue, Date changeTime, String operator) {
        this.key = key;
        this.newValue = newValue;
        this.oldValue = oldValue;
        this.changeTime = changeTime;
        this.operator = operator;
    }

    // Getters
    public String getKey() { return key; }
    public String getNewValue() { return newValue; }
    public String getOldValue() { return oldValue; }
    public Date getChangeTime() { return changeTime; }
    public String getOperator() { return operator; }

    @Override
    public String toString() {
        return "ConfigChangeEvent{" +
                "key='" + key + '\'' +
                ", newValue='" + newValue + '\'' +
                ", oldValue='" + oldValue + '\'' +
                ", changeTime=" + changeTime +
                ", operator='" + operator + '\'' +
                '}';
    }
}