package org.noear.nimbus.solon.cloud;

import com.nimbus.client.ConfigClientFactory;
import com.nimbus.client.NimBusClient;
import com.nimbus.client.NimbusConfigClient;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigService;

import java.util.Arrays;

/**
 * Nimbus 配置服务实现
 */
public class NimbusCloudConfigService implements CloudConfigService {

    private final String appName;
    private final String env;

    public NimbusCloudConfigService(CloudProps cloudProps) {
        this.appName = cloudProps.getNamespace();
        this.env = cloudProps.getValue("env");
        // 初始化配置服务
        NimbusConfigClient.init(Arrays.asList(cloudProps.getServer().split(",")), appName, env);
    }

    @Override
    public Config pull(String group, String name) {
        String value = NimBusClient.get(name, null);
        if (value != null) {
            return new Config(group, name, value, 1);
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
        // 使用静态方法注册配置监听器
        ConfigClientFactory.addStaticConfigListener(appName, env, name, event -> {
            Config config = new Config(group, name, event.getNewValue(), 1);
            observer.handle(config);
        });
    }
}