package cloud.xuantong.solon.integration;

import org.noear.solon.annotation.BindProps;

import java.util.List;

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
}
