package org.noear.nimbus.solon.cloud;

import com.nimbus.client.NimbusConfig;
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
public class NimbusCloudAutoConfiguration implements Plugin {


    @Override
    public void start(AppContext context) throws Throwable{
        CloudProps cloudProps = new CloudProps(context, "nimbus-conf");
        if (Utils.isEmpty(cloudProps.getServer())) {
            return;
        }
        if (Utils.isEmpty(cloudProps.getNamespace())){
            throw new CloudException("nimbus-conf.namespace必须配置，格式为 name:env");
        }
        if (cloudProps.getConfigEnable()) {
            // 注册配置服务
            CloudManager.register(new NimbusCloudConfigService(cloudProps));
            //加载配置
            //String configLoad = cloudProps.getConfigLoad();
            String configLoad = context.cfg().get("solon.cloud.nimbus-conf.load");
            CloudClient.configLoad(configLoad);
        }
    }
    @Override
    public void stop() throws Throwable {
        NimbusConfig.close();
    }
}