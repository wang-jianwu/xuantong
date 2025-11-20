package com.nimbus.config.autoconfigure;
import com.nimbus.client.NimBusClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

@Configuration
@ConditionalOnClass(NimBusClient.class)
@EnableConfigurationProperties(NimbusConfigProperties.class)
public class NimbusConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NimBusClient nimBusClient(NimbusConfigProperties properties) {
        return new NimBusClient(
            properties.getServerAddresses(),
            properties.getAppName(),
            properties.getEnvironment()
        );
    }

    @Bean
    public NimbusConfigValueProcessor nimbusConfigValueProcessor(ObjectProvider<NimBusClient> nimBusClientProvider) {
        return new NimbusConfigValueProcessor(nimBusClientProvider);
    }
}