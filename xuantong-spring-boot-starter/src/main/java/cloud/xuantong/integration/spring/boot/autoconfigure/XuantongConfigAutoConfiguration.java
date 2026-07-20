package cloud.xuantong.integration.spring.boot.autoconfigure;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.XuantongConfigClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnClass(XuantongConfigClient.class)
@ConditionalOnMissingClass("cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudAutoConfiguration")
@EnableConfigurationProperties(XuantongConfigProperties.class)
public class XuantongConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public XuantongConfigClient xuantongConfigClient(
            XuantongConfigProperties properties, Environment environment) {
        String applicationName = properties.getApplicationName();
        if (applicationName == null || applicationName.trim().isEmpty()) {
            applicationName = environment.getProperty("spring.application.name", "spring-application");
        }
        ControlPlaneOptions defaults = ControlPlaneOptions.defaults();
        ControlPlaneOptions controlPlaneOptions = new ControlPlaneOptions(
                properties.getTenant(),
                properties.getStateGroupId(),
                properties.getClusterId(),
                properties.getTransportGeneration(),
                properties.getTransportPool(),
                defaults.connectTimeoutMs(),
                defaults.requestTimeoutMs(),
                defaults.operationTimeoutMs(),
                defaults.closingTimeoutMs(),
                properties.getTls().toOptions());
        return new XuantongConfigClient(
                properties.getServerAddresses(),
                properties.getNamespace(),
                properties.getGroup(),
                properties.getAccessToken(),
                new ClientIdentity(applicationName, properties.getClientInstanceId()),
                controlPlaneOptions
        );
    }

    @Bean
    public static XuantongConfigValueProcessor xuantongConfigValueProcessor(
            ObjectProvider<XuantongConfigClient> xuantongConfigClientProvider) {
        return new XuantongConfigValueProcessor(xuantongConfigClientProvider);
    }
}
