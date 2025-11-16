package com.nimbus.client.listener;

import com.nimbus.client.model.ConfigChangeEvent;

/**
 * 配置变更监听器接口
 */
@FunctionalInterface
public interface ConfigListener {
    /**
     * 配置变更时触发
     * @param event 配置变更事件
     */
    void onConfigChange(ConfigChangeEvent event);
}