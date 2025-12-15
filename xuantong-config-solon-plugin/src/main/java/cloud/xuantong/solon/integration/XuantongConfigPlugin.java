package cloud.xuantong.solon.integration;

import cloud.xuantong.client.XuantongConfig;
import cloud.xuantong.client.annotation.ConfigValue;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author 封于修
 * Xuantong Config Solon Plugin
 * 集成xuantong-client的ConfigValue注解到Solon IoC容器
 */
public class XuantongConfigPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(XuantongConfigPlugin.class);

    @Inject(value = "xuantong.config")
    private XuantongConfigProperties xuantongProps;

    @Override
    public void start(AppContext context) {
        XuantongConfigProperties configBindings = context.cfg().bindTo(XuantongConfigProperties.class);
        if (configBindings.getServerAddresses() == null || configBindings.getServerAddresses().isEmpty()) {
            logger.warn("Xuantong-config server address is empty");
            return;
        }
        XuantongConfig.init(configBindings.getServerAddresses(), configBindings.getAppNames(), configBindings.getEnvironment());
        // 注册ConfigValue 注入器
        context.beanInjectorAdd(ConfigValue.class, new XuantongConfigValueInjector());

        logger.info("Xuantong-config Solon Plugin started successfully");
    }

    @Override
    public void stop() throws Throwable {
        logger.info("Xuantong-config Solon Plugin stopped");
        XuantongConfig.close();
    }
}