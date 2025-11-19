package org.noear.nimbus.solon.cloud;

import com.nimbus.client.NimBusClient;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;

import java.util.Arrays;

/**
 * Nimbus 配置服务实现
 */
public class NimbusCloudConfigService implements CloudConfigService {

    private final NimBusClient client;

    public NimbusCloudConfigService(CloudProps cloudProps) {
        String namespace = cloudProps.getNamespace();
        String[] s = namespace.split("_");
        String appName = s[0];
        String env = s[1];
        // 创建配置客户端实例
        this.client = new NimBusClient(
            Arrays.asList(cloudProps.getServer().split(",")),
                appName,
                env
        );
    }
    @Override
    public Config pull(String group, String name) {
        String value = client.get(name, null);
        if (value != null) {
            return new Config(group, name, value, 0);
        }
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
        CloudConfigObserverEntity entity = new CloudConfigObserverEntity(group, name, observer);

        // 注册配置监听器
        client.addListener(entity.key, event ->
                entity.handler.handle(new Config(entity.group, entity.key, event.getNewValue(), 0)));
    }
}