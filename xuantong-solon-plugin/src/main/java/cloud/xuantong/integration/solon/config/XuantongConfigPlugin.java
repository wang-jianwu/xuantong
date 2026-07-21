package cloud.xuantong.integration.solon.config;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.XuantongConfigClient;
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
    private XuantongConfigClient configClient;
    private XuantongConfigValueInjector valueInjector;

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
            ControlPlaneOptions defaults = ControlPlaneOptions.defaults();
            ControlPlaneOptions controlPlaneOptions = new ControlPlaneOptions(
                    configBindings.getTenant(),
                    configBindings.getStateGroupId(),
                    configBindings.getClusterId(),
                    configBindings.getTransportGeneration(),
                    configBindings.getTransportPool(),
                    defaults.connectTimeoutMs(),
                    defaults.requestTimeoutMs(),
                    defaults.operationTimeoutMs(),
                    defaults.closingTimeoutMs(),
                    configBindings.getTls().toOptions());
            configClient = new XuantongConfigClient(
                    configBindings.getServerAddresses(),
                    configBindings.getNamespace(),
                    configBindings.getGroup(),
                    configBindings.getAccessToken(),
                    new ClientIdentity(applicationName, configBindings.getClientInstanceId()),
                    controlPlaneOptions,
                    configBindings.clientOptions());
        } catch (Exception e) {
            logger.error("XuantongConfig init failed, config will not be available: {}", e.getMessage());
            return;
        }
        // 注册ConfigValue 注入器
        valueInjector = new XuantongConfigValueInjector(configClient);
        context.beanInjectorAdd(ConfigValue.class, valueInjector);

        logger.info("Xuantong Solon Plugin started successfully");
    }

    @Override
    public void stop() throws Throwable {
        logger.info("Xuantong Solon Plugin stopped");
        if (valueInjector != null) {
            valueInjector.close();
            valueInjector = null;
        }
        if (configClient != null) {
            configClient.close();
            configClient = null;
        }
    }
}
