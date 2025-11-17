package org.noear.nimbus.solon.cloud;

import org.noear.solon.Utils;
import org.noear.solon.cloud.CloudClient;
import org.noear.solon.cloud.CloudManager;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * Nimbus Solon Cloud 自动配置插件
 */
public class NimbusCloudAutoConfiguration implements Plugin {


    @Override
    public void start(AppContext context) {
        CloudProps cloudProps = new CloudProps(context, "nimbus-config");
        if (Utils.isEmpty(cloudProps.getServer())) {
            return;
        }
        //1.登记配置服务
        if (cloudProps.getConfigEnable()) {
            // 注册配置服务
            CloudManager.register(new NimbusCloudConfigService(cloudProps));
            //加载配置
            CloudClient.configLoad(cloudProps.getConfigLoad());
        }
    }

    @Override
    public void stop() throws Throwable {
        Plugin.super.stop();
    }
}