package cloud.xuantong.integration.solon.cloud;

import org.noear.solon.Utils;
import org.noear.solon.Solon;
import org.noear.solon.cloud.CloudClient;
import org.noear.solon.cloud.CloudManager;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.exception.CloudException;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.noear.solon.core.Signal;
import org.noear.solon.core.event.AppLoadEndEvent;
import org.noear.solon.cloud.model.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玄同 Solon Cloud 自动配置插件
 */
public class XuantongConfigAutoConfiguration implements Plugin {
    private static final Logger log = LoggerFactory.getLogger(
            XuantongConfigAutoConfiguration.class);

    private static final String CLOUD_CONFIG = "xuantong";
    private static final String ERROR_MESSAGE = "solon.cloud.xuantong.namespace 必须配置";
    private XuantongCloudConfigService configService;
    private XuantongCloudDiscoveryService discoveryService;
    private Boolean previousCloudRegisterEnabled;
    private final Set<String> registeredSignals = ConcurrentHashMap.newKeySet();

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
            previousCloudRegisterEnabled = CloudClient.enableRegister();
            CloudClient.enableRegister(false);
            registerDiscoveryLifecycle();
        }
    }

    private void registerDiscoveryLifecycle() {
        Solon.app().onEvent(AppLoadEndEvent.class, event -> {
            for (Signal signal : Solon.app().signals()) {
                registerSignal(signal);
            }
        });
        Solon.app().onEvent(Signal.class, this::registerSignal);
    }

    private void registerSignal(Signal signal) {
        Instance instance = Instance.localNew(signal);
        String key = Solon.cfg().appGroup() + '\u0000' + instance.serviceAndAddress();
        if (!registeredSignals.add(key)) {
            return;
        }
        try {
            discoveryService.register(Solon.cfg().appGroup(), instance);
            log.info("Cloud: Service registered {}@{}", instance.service(), instance.uri());
        } catch (RuntimeException e) {
            registeredSignals.remove(key);
            throw e;
        }
    }
    @Override
    public void stop() throws Throwable {
        if (discoveryService != null) {
            discoveryService.close();
            discoveryService = null;
        }
        registeredSignals.clear();
        if (previousCloudRegisterEnabled != null) {
            CloudClient.enableRegister(previousCloudRegisterEnabled);
            previousCloudRegisterEnabled = null;
        }
        if (configService != null) {
            configService.close();
            configService = null;
        }
    }
}
