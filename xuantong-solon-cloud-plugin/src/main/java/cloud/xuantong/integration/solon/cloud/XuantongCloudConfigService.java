package cloud.xuantong.integration.solon.cloud;

import cloud.xuantong.client.XuantongConfigClient;
import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import org.noear.solon.Solon;
import org.noear.solon.cloud.CloudConfigHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.cloud.exception.CloudException;
import org.noear.solon.cloud.model.Config;
import org.noear.solon.cloud.service.CloudConfigObserverEntity;
import org.noear.solon.cloud.service.CloudConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玄同配置服务实现
 */
public class XuantongCloudConfigService implements CloudConfigService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(XuantongCloudConfigService.class);
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    private final List<String> serverAddresses;
    private final String namespace;
    private final String accessToken;
    private final ClientIdentity clientIdentity;
    private final ControlPlaneOptions controlPlaneOptions;
    private final Map<String, XuantongConfigClient> clients = new ConcurrentHashMap<>();

    public XuantongCloudConfigService(CloudProps cloudProps) {
        this.namespace = cloudProps.getNamespace() == null ? "" : cloudProps.getNamespace().trim();
        if (namespace.isEmpty()) {
            throw new CloudException("solon.cloud.xuantong.namespace 不能为空");
        }

        this.serverAddresses = parseServerAddresses(cloudProps.getConfigServer());
        if (serverAddresses.isEmpty()) {
            throw new CloudException("solon.cloud.xuantong.server 不能为空");
        }

        String token = cloudProps.getToken();
        this.accessToken = token == null ? "" : token.trim();
        this.clientIdentity = new ClientIdentity(Solon.cfg().appName(), null);
        ControlPlaneOptions defaults = ControlPlaneOptions.defaults();
        this.controlPlaneOptions = new ControlPlaneOptions(
                Solon.cfg().get("solon.cloud.xuantong.tenant", defaults.tenant()),
                Solon.cfg().get("solon.cloud.xuantong.config.stateGroupId",
                        defaults.stateGroupId()),
                Solon.cfg().get("solon.cloud.xuantong.clusterId", defaults.clusterId()),
                Solon.cfg().getLong("solon.cloud.xuantong.transportGeneration",
                        defaults.transportGeneration()),
                Solon.cfg().get("solon.cloud.xuantong.transportPool",
                        defaults.transportPool()),
                defaults.connectTimeoutMs(),
                defaults.requestTimeoutMs(),
                defaults.operationTimeoutMs(),
                defaults.closingTimeoutMs());
    }

    @Override
    public Config pull(String group, String name) {
        String normalizedGroup = normalizeGroup(group);
        String value = clientFor(normalizedGroup).get(name, null);
        if (value != null) {
            return new Config(normalizedGroup, name, value, 0);
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
        String normalizedGroup = normalizeGroup(group);
        CloudConfigObserverEntity entity = new CloudConfigObserverEntity(normalizedGroup, name, observer);
        //配置监听器
        clientFor(normalizedGroup).addListener(entity.key, event -> {
            // 配置被删除时（value=null），不通知 observer（CloudConfig 不支持 null value）
            if (event.getNewValue() == null) {
                log.warn("cloud config deleted: {}, observer not notified", entity.key);
                return;
            }
            log.info("cloud config change: {} -> {}", entity.key, event.getNewValue());
            entity.handler.handle(new Config(entity.group, entity.key, event.getNewValue(), 0));
        });
    }

    private XuantongConfigClient clientFor(String group) {
        return clients.computeIfAbsent(group,
                item -> new XuantongConfigClient(
                        serverAddresses,
                        namespace,
                        item,
                        accessToken,
                        clientIdentity,
                        controlPlaneOptions));
    }

    private String normalizeGroup(String group) {
        if (group == null || group.trim().isEmpty()) {
            return DEFAULT_GROUP;
        }
        return group.trim();
    }

    private List<String> parseServerAddresses(String server) {
        List<String> addresses = new ArrayList<>();
        if (server == null) {
            return addresses;
        }
        for (String address : server.split(",")) {
            String trimmed = address.trim();
            if (!trimmed.isEmpty()) {
                addresses.add(trimmed);
            }
        }
        return addresses;
    }

    @Override
    public void close() {
        for (XuantongConfigClient client : clients.values()) {
            client.close();
        }
        clients.clear();
    }
}
