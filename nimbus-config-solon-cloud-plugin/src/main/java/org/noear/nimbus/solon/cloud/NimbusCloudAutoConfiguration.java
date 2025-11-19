package org.noear.nimbus.solon.cloud;

import com.nimbus.client.NimBusClient;
import com.nimbus.client.NimbusConfig;
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
    public void start(AppContext context) throws Throwable{
        CloudProps cloudProps = new CloudProps(context, "nimbus-conf");
        if (Utils.isEmpty(cloudProps.getServer())) {
            return;
        }
        if (Utils.isEmpty(cloudProps.getNamespace())){
            throw new RuntimeException("nimbus-conf.namespace 和 env 必须配置");
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
        NimbusConfig.close();
    }
}