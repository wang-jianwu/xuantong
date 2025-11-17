package org.noear.nimbus.solon.cloud;

import com.nimbus.client.ConfigClientFactory;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigService;

/**
 * Nimbus 配置服务实现
 */
public class NimbusCloudConfigService implements CloudConfigService {

    public NimbusCloudConfigService(CloudProps  cloudProps) {
        // 初始化配置服务
        ConfigClientFactory.init(cloudProps.getServer(), cloudProps.getNamespace(), "");
    }

    @Override
    public Config pull(String group, String name) {
        return null;
    }

    @Override
    public boolean push(String group, String name, String value) {
        return false;
    }

    @Override
    public boolean remove(String group, String name) {
        return false;
    }

    @Override
    public void attention(String group, String name, CloudConfigHandler observer) {

    }
}