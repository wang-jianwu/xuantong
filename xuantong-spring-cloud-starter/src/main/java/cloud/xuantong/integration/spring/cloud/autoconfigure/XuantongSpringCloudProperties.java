package cloud.xuantong.integration.spring.cloud.autoconfigure;

import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.TlsOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Cloud integration settings shared by configuration and discovery.
 */
@ConfigurationProperties(XuantongSpringCloudProperties.PREFIX)
public class XuantongSpringCloudProperties {
    public static final String PREFIX = "spring.cloud.xuantong";

    private boolean enabled = true;
    private List<String> serverAddresses = new ArrayList<>(List.of("127.0.0.1:8090"));
    private String namespace = "public";
    private String group = "DEFAULT_GROUP";
    private String accessToken = "";
    private String applicationName;
    private String clientInstanceId;
    private String tenant = "default";
    private String clusterId = "";
    private long transportGeneration;
    private String transportPool = "tcp-default";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(3);
    private Duration operationTimeout = Duration.ofSeconds(6);
    private Duration closingTimeout = Duration.ofSeconds(3);
    private final Tls tls = new Tls();
    private final Config config = new Config();
    private final Discovery discovery = new Discovery();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getServerAddresses() {
        return serverAddresses;
    }

    public void setServerAddresses(List<String> serverAddresses) {
        this.serverAddresses = serverAddresses == null ? new ArrayList<>() : serverAddresses;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getClientInstanceId() {
        return clientInstanceId;
    }

    public void setClientInstanceId(String clientInstanceId) {
        this.clientInstanceId = clientInstanceId;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public long getTransportGeneration() {
        return transportGeneration;
    }

    public void setTransportGeneration(long transportGeneration) {
        this.transportGeneration = transportGeneration;
    }

    public String getTransportPool() {
        return transportPool;
    }

    public void setTransportPool(String transportPool) {
        this.transportPool = transportPool;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getOperationTimeout() {
        return operationTimeout;
    }

    public void setOperationTimeout(Duration operationTimeout) {
        this.operationTimeout = operationTimeout;
    }

    public Duration getClosingTimeout() {
        return closingTimeout;
    }

    public void setClosingTimeout(Duration closingTimeout) {
        this.closingTimeout = closingTimeout;
    }

    public Tls getTls() {
        return tls;
    }

    public Config getConfig() {
        return config;
    }

    public Discovery getDiscovery() {
        return discovery;
    }

    public ControlPlaneOptions configControlPlaneOptions() {
        return controlPlaneOptions(config.getStateGroupId());
    }

    public ControlPlaneOptions discoveryControlPlaneOptions() {
        return controlPlaneOptions(discovery.getStateGroupId());
    }

    private ControlPlaneOptions controlPlaneOptions(String stateGroupId) {
        return new ControlPlaneOptions(
                tenant,
                stateGroupId,
                clusterId,
                transportGeneration,
                transportPool,
                millis("connect-timeout", connectTimeout),
                millis("request-timeout", requestTimeout),
                millis("operation-timeout", operationTimeout),
                millisAllowZero("closing-timeout", closingTimeout),
                tls.toOptions());
    }

    private long millis(String name, Duration value) {
        long millis = millisAllowZero(name, value);
        if (millis <= 0L) {
            throw new IllegalArgumentException(PREFIX + "." + name + " must be positive");
        }
        return millis;
    }

    private long millisAllowZero(String name, Duration value) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(PREFIX + "." + name + " must not be negative");
        }
        return value.toMillis();
    }

    public static class Config {
        private boolean enabled = true;
        private String stateGroupId = "config-default";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStateGroupId() {
            return stateGroupId;
        }

        public void setStateGroupId(String stateGroupId) {
            this.stateGroupId = stateGroupId;
        }
    }

    public static class Tls {
        private boolean enabled;
        private String trustStore = "";
        private String trustStoreType = "PKCS12";
        private String trustStorePassword = "";
        private String keyStore = "";
        private String keyStoreType = "PKCS12";
        private String keyStorePassword = "";
        private String keyPassword = "";
        private boolean hostnameVerification = true;
        private long reloadIntervalMs = 30_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTrustStore() { return trustStore; }
        public void setTrustStore(String trustStore) { this.trustStore = trustStore; }
        public String getTrustStoreType() { return trustStoreType; }
        public void setTrustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
        }
        public String getTrustStorePassword() { return trustStorePassword; }
        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }
        public String getKeyStore() { return keyStore; }
        public void setKeyStore(String keyStore) { this.keyStore = keyStore; }
        public String getKeyStoreType() { return keyStoreType; }
        public void setKeyStoreType(String keyStoreType) { this.keyStoreType = keyStoreType; }
        public String getKeyStorePassword() { return keyStorePassword; }
        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }
        public String getKeyPassword() { return keyPassword; }
        public void setKeyPassword(String keyPassword) { this.keyPassword = keyPassword; }
        public boolean isHostnameVerification() { return hostnameVerification; }
        public void setHostnameVerification(boolean hostnameVerification) {
            this.hostnameVerification = hostnameVerification;
        }
        public long getReloadIntervalMs() { return reloadIntervalMs; }
        public void setReloadIntervalMs(long reloadIntervalMs) {
            this.reloadIntervalMs = reloadIntervalMs;
        }

        public TlsOptions toOptions() {
            return new TlsOptions(
                    enabled, trustStore, trustStoreType, trustStorePassword,
                    keyStore, keyStoreType, keyStorePassword, keyPassword,
                    hostnameVerification, reloadIntervalMs);
        }
    }

    public static class Discovery {
        private boolean enabled = true;
        private boolean register = true;
        private String stateGroupId = "registry-default";
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private String serviceName;
        private String instanceId;
        private String ipAddress;
        private int port;
        private boolean secure;
        private double weight = 1D;
        private Map<String, String> metadata = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRegister() {
            return register;
        }

        public void setRegister(boolean register) {
            this.register = register;
        }

        public String getStateGroupId() {
            return stateGroupId;
        }

        public void setStateGroupId(String stateGroupId) {
            this.stateGroupId = stateGroupId;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
        }
    }
}
