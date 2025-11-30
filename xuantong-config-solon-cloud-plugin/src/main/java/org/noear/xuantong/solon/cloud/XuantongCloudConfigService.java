package org.noear.xuantong.solon.cloud;

import cloud.xuantong.client.XuantongClient;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.exception.CloudException;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Nimbus 配置服务实现
 */
public class XuantongCloudConfigService implements CloudConfigService {

    private static final Logger log = LoggerFactory.getLogger(XuantongCloudConfigService.class);
    private final XuantongClient client;

    public XuantongCloudConfigService(CloudProps cloudProps) {
        String namespace = cloudProps.getNamespace();
        // 解析配置格式：env:appName,app1,app2
        // 多应用模式
        String[] env_name = namespace.split(":");
        if (env_name.length != 2) {
            throw new CloudException("配置格式错误，应为 env:appName,app2");
        }
        String env = env_name[0];
        String appNames = env_name[1];
        if (env == null || env.trim().isEmpty() || appNames == null || appNames.trim().isEmpty()) {
            throw new CloudException("环境不能为空");
        }
        // 创建配置客户端实例
        this.client = new XuantongClient(
                Arrays.asList(cloudProps.getServer().trim().split(",")),
                Arrays.asList(appNames.trim().split(",")),
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