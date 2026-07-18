package cloud.xuantong.integration.spring.cloud.config;

import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;

import java.util.List;

record XuantongConfigDataSettings(
        boolean enabled,
        List<String> serverAddresses,
        String namespace,
        String group,
        String accessToken,
        String applicationName,
        String clientInstanceId,
        ControlPlaneOptions controlPlaneOptions) {

    static XuantongConfigDataSettings from(
            XuantongSpringCloudProperties properties, String applicationName) {
        return new XuantongConfigDataSettings(
                properties.isEnabled() && properties.getConfig().isEnabled(),
                List.copyOf(properties.getServerAddresses()),
                properties.getNamespace(),
                properties.getGroup(),
                properties.getAccessToken() == null ? "" : properties.getAccessToken(),
                applicationName,
                properties.getClientInstanceId(),
                properties.configControlPlaneOptions());
    }
}
