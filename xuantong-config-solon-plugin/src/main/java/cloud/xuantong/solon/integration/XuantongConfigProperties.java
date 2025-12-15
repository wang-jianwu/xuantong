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
    private List<String> appNames;
    private String environment;

    public List<String> getServerAddresses() {
        return serverAddresses;
    }

    public void setServerAddresses(List<String> serverAddresses) {
        this.serverAddresses = serverAddresses;
    }

    public List<String> getAppNames() {
        return appNames;
    }

    public void setAppNames(List<String> appNames) {
        this.appNames = appNames;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }
}
