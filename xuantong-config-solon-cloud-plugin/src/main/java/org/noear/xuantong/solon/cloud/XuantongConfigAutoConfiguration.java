package org.noear.xuantong.solon.cloud;

import cloud.xuantong.client.XuantongConfig;
import org.noear.solon.Utils;
import org.noear.solon.cloud.CloudClient;
import org.noear.solon.cloud.CloudManager;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.exception.CloudException;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * Nimbus Solon Cloud 自动配置插件
 */
public class XuantongConfigAutoConfiguration implements Plugin {

    private static final String CLOUD_CONFIG = "xuantong";
    private static final String ERROR_MESSAGE = "xuantong.namespace必须配置[格式为 env:subscribedApp1,subscribedApp2...]";

    @Override
    public void start(AppContext context) throws Throwable{
        CloudProps cloudProps = new CloudProps(context, CLOUD_CONFIG);
        if (Utils.isEmpty(cloudProps.getServer())) {
            return;
        }
        if (Utils.isEmpty(cloudProps.getNamespace())){
            throw new CloudException(ERROR_MESSAGE);
        }
        if (cloudProps.getConfigEnable()) {
            // 注册配置服务
            CloudManager.register(new XuantongCloudConfigService(cloudProps));
            //加载配置
            String configLoad = cloudProps.getConfigLoad();
            CloudClient.configLoad(configLoad);
        }
    }
    @Override
    public void stop() throws Throwable {
        XuantongConfig.close();
    }
}