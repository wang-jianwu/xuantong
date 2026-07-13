package org.noear.xuantong.solon.cloud;

import org.noear.solon.Utils;
import org.noear.solon.cloud.CloudClient;
import org.noear.solon.cloud.CloudManager;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.exception.CloudException;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * 玄同 Solon Cloud 自动配置插件
 */
public class XuantongConfigAutoConfiguration implements Plugin {

    private static final String CLOUD_CONFIG = "xuantong";
    private static final String ERROR_MESSAGE = "solon.cloud.xuantong.namespace 必须配置";
    private XuantongCloudConfigService configService;
    private XuantongCloudDiscoveryService discoveryService;

    @Override
    public void start(AppContext context) throws Throwable{
        CloudProps cloudProps = new CloudProps(context, CLOUD_CONFIG);
        if (Utils.isEmpty(cloudProps.getNamespace())){
            throw new CloudException(ERROR_MESSAGE);
        }
        boolean configEnabled = cloudProps.getConfigEnable();
        boolean discoveryEnabled = cloudProps.getDiscoveryEnable();
        if (!configEnabled && !discoveryEnabled) {
            return;
        }
        if (configEnabled && Utils.isEmpty(cloudProps.getConfigServer())) {
            throw new CloudException("solon.cloud.xuantong.config.server 必须配置");
        }
        if (discoveryEnabled && Utils.isEmpty(cloudProps.getDiscoveryServer())) {
            throw new CloudException("solon.cloud.xuantong.discovery.server 必须配置");
        }
        if (configEnabled) {
            // 注册配置服务
            configService = new XuantongCloudConfigService(cloudProps);
            CloudManager.register(configService);
            //加载配置
            String configLoad = cloudProps.getConfigLoad();
            CloudClient.configLoad(configLoad);
        }
        if (discoveryEnabled) {
            discoveryService = new XuantongCloudDiscoveryService(cloudProps);
            CloudManager.register(discoveryService);
            CloudClient.discoveryPush();
        }
    }
    @Override
    public void stop() throws Throwable {
        if (discoveryService != null) {
            discoveryService.close();
            discoveryService = null;
        }
        if (configService != null) {
            configService.close();
            configService = null;
        }
    }
}
