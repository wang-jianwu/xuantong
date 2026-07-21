package cloud.xuantong.integration.solon.config;

import cloud.xuantong.client.TlsOptions;
import cloud.xuantong.client.ConfigClientOptions;
import org.noear.solon.annotation.BindProps;

import java.util.List;
import java.nio.file.Path;

/**
 * author 封于修
 * date 2025/12/14 19:43
 */
@BindProps(prefix = "xuantong.config")
public class XuantongConfigProperties {
    private List<String> serverAddresses;
    private String namespace = "public";
    private String group = "DEFAULT_GROUP";
    private String accessToken = "";
    private String applicationName = "solon-application";
    private String clientInstanceId;
    private String stateGroupId = "config-default";
    private String clusterId = "";
    private long transportGeneration;
    private String transportPool = "tcp-default";
    private String tenant = "default";
    private String cacheDirectory = "";
    private Tls tls = new Tls();

    public List<String> getServerAddresses() {
        return serverAddresses;
    }

    public void setServerAddresses(List<String> serverAddresses) {
        this.serverAddresses = serverAddresses;
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

    public String getStateGroupId() { return stateGroupId; }
    public void setStateGroupId(String stateGroupId) { this.stateGroupId = stateGroupId; }
    public String getClusterId() { return clusterId; }
    public void setClusterId(String clusterId) { this.clusterId = clusterId; }
    public long getTransportGeneration() { return transportGeneration; }
    public void setTransportGeneration(long transportGeneration) {
        this.transportGeneration = transportGeneration;
    }
    public String getTransportPool() { return transportPool; }
    public void setTransportPool(String transportPool) { this.transportPool = transportPool; }
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public String getCacheDirectory() { return cacheDirectory; }
    public void setCacheDirectory(String cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }
    public ConfigClientOptions clientOptions() {
        return new ConfigClientOptions(cacheDirectory == null || cacheDirectory.isBlank()
                ? null : Path.of(cacheDirectory.trim()));
    }
    public Tls getTls() { return tls; }
    public void setTls(Tls tls) { this.tls = tls == null ? new Tls() : tls; }

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
}
