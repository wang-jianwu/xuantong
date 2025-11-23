package org.noear.xuantong.solon.cloud;

import com.xuantong.client.XuantongClient;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Nimbus 配置服务实现
 */
public class XuantongCloudConfigService implements CloudConfigService {

    private static final Logger log = LoggerFactory.getLogger(XuantongCloudConfigService.class);
    private final XuantongClient client;

    public XuantongCloudConfigService(CloudProps cloudProps) {
        String namespace = cloudProps.getNamespace();

        // 解析配置格式：支持两种格式
        // 1. 单应用模式：appName:env
        // 2. 多应用模式：appName:env;app1,app2,app3
        String appName;
        String env;
        List<String> subscribedApps = java.util.Collections.emptyList();

        if (namespace.contains(";")) {
            // 多应用模式
            String[] parts = namespace.split(";");
            String[] name_env = parts[0].split(":");
            appName = name_env[0];
            env = name_env[1];
            subscribedApps = Arrays.asList(parts[1].split(","));
        } else {
            // 单应用模式
            String[] name_env = namespace.split(":");
            appName = name_env[0];
            env = name_env[1];
        }

        // 创建配置客户端实例
        this.client = new XuantongClient(
                Arrays.asList(cloudProps.getServer().split(",")),
                appName,
                subscribedApps,
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
        //配置监听器
        client.addListener(entity.key, event -> {
            log.info("cloud config change: {} -> {}", entity.key, event.getNewValue());
            entity.handler.handle(new Config(entity.group, entity.key, event.getNewValue(), 0));
        });
    }
}