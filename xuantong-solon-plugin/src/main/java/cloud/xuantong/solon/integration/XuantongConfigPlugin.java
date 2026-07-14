package cloud.xuantong.solon.integration;

import cloud.xuantong.client.XuantongConfig;
import cloud.xuantong.client.annotation.ConfigValue;
import org.noear.solon.annotation.Inject;
import org.noear.solon.Solon;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author 封于修
 * 玄同 Solon 配置插件
 * 集成 xuantong-client-core 的 ConfigValue 注解到 Solon IoC 容器
 */
public class XuantongConfigPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(XuantongConfigPlugin.class);

    @Inject(value = "xuantong.config")
    private XuantongConfigProperties xuantongProps;

    @Override
    public void start(AppContext context) {
        XuantongConfigProperties configBindings = context.cfg().bindTo(XuantongConfigProperties.class);
        if (configBindings.getServerAddresses() == null || configBindings.getServerAddresses().isEmpty()) {
            logger.warn("Xuantong server address is empty");
            return;
        }
        try {
            String applicationName = configBindings.getApplicationName();
            if (applicationName == null || applicationName.trim().isEmpty()
                    || "solon-application".equals(applicationName)) {
                applicationName = Solon.cfg().appName();
            }
            XuantongConfig.init(
                    configBindings.getServerAddresses(),
                    configBindings.getNamespace(),
                    configBindings.getGroup(),
                    configBindings.getAccessToken(),
                    applicationName,
                    configBindings.getClientInstanceId());
        } catch (Exception e) {
            logger.error("XuantongConfig init failed, config will not be available: {}", e.getMessage());
            // 不抛出异常，让应用继续启动。配置注入会在配置中心可用后获取到值
        }
        // 注册ConfigValue 注入器
        context.beanInjectorAdd(ConfigValue.class, new XuantongConfigValueInjector());

        logger.info("Xuantong Solon Plugin started successfully");
    }

    @Override
    public void stop() throws Throwable {
        logger.info("Xuantong Solon Plugin stopped");
        XuantongConfig.close();
    }
}
