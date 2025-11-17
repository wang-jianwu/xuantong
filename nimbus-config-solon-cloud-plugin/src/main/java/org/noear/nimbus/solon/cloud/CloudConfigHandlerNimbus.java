package org.noear.nimbus.solon.cloud;

import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.model.Config;

/**
 * Nimbus配置变更处理器
 */
public class CloudConfigHandlerNimbus implements CloudConfigHandler {

    @Override
    public void handle(Config config) {
        // 处理配置变更事件
        System.out.println("Nimbus config changed: " + config.key() + " = " + config.value());
    }
}