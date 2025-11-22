package org.noear.xuantong.solon.cloud;

import com.xuantong.client.XuantongClient;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;

import java.util.Arrays;

/**
 * Nimbus 配置服务实现
 */
public class XuantongCloudConfigService implements CloudConfigService {

    private final XuantongClient client;

    public XuantongCloudConfigService(CloudProps cloudProps) {
        String namespace = cloudProps.getNamespace();
        String[] name_env = namespace.split(":");
        String appName = name_env[0];
        String env = name_env[1];
        // 创建配置客户端实例
        this.client = new XuantongClient(
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