package cloud.xuantong.config.autoconfigure;

import cloud.xuantong.client.XuantongClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnClass(XuantongClient.class)
@EnableConfigurationProperties(XuantongConfigProperties.class)
public class XuantongConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public XuantongClient xuantongClient(
            XuantongConfigProperties properties, Environment environment) {
        String applicationName = properties.getApplicationName();
        if (applicationName == null || applicationName.trim().isEmpty()) {
            applicationName = environment.getProperty("spring.application.name", "spring-application");
        }
        return new XuantongClient(
                properties.getServerAddresses(),
                properties.getNamespace(),
                properties.getGroup(),
                properties.getAccessToken(),
                applicationName,
                properties.getClientInstanceId()
        );
    }

    @Bean
    public static XuantongConfigValueProcessor xuantongConfigValueProcessor(
            ObjectProvider<XuantongClient> xuantongClientProvider) {
        return new XuantongConfigValueProcessor(xuantongClientProvider);
    }
}
