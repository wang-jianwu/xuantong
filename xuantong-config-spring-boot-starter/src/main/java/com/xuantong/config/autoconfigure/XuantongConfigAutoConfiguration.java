package com.xuantong.config.autoconfigure;

import com.xuantong.client.XuantongClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(XuantongClient.class)
@EnableConfigurationProperties(XuantongConfigProperties.class)
public class XuantongConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public XuantongClient nimBusClient(XuantongConfigProperties properties) {
        return new XuantongClient(
                properties.getServerAddresses(),
                properties.getAppName(),
                properties.getEnvironment()
        );
    }

    @Bean
    public XuantongConfigValueProcessor nimbusConfigValueProcessor(ObjectProvider<XuantongClient> xuantongClientProvider) {
        return new XuantongConfigValueProcessor(xuantongClientProvider);
    }
}