package cloud.xuantong.config.autoconfigure;

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

    /** 客户端实例标识；留空时按应用名和 JVM 进程自动生成。 */
    private String clientId;

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

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
