package cloud.xuantong.integration.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "xuantong.config")
public class XuantongConfigProperties {

    /**
     * 玄同配置服务地址列表
     */
    private List<String> serverAddresses;

    /**
     * 命名空间
     */
    private String namespace = "public";

    /**
     * 分组
     */
    private String group = "DEFAULT_GROUP";

    /**
     * 客户端访问令牌
     */
    private String accessToken = "";

    /** 应用名称；留空时使用 spring.application.name。 */
    private String applicationName;

    /** 客户端运行实例标识；通常无需配置，由客户端自动生成。 */
    private String clientInstanceId;

    /** Config State Group；所有 Gateway 必须路由到同一个权威组。 */
    private String stateGroupId = "config-default";

    /** 控制面集群标识；留空时由首次成功 Hello 自动锁定。 */
    private String clusterId = "";

    /** 传输代次；0 表示由首次成功 Hello 自动锁定。 */
    private long transportGeneration;

    /** 基础设施兼容池；自动故障切换不会跨池。 */
    private String transportPool = "tcp-default";

    /** 租户标识。 */
    private String tenant = "default";

    // Getters and Setters

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

    public String getStateGroupId() {
        return stateGroupId;
    }

    public void setStateGroupId(String stateGroupId) {
        this.stateGroupId = stateGroupId;
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

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }
}
