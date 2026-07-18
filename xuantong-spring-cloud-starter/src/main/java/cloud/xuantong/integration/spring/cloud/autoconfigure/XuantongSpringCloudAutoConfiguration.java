package cloud.xuantong.integration.spring.cloud.autoconfigure;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.XuantongConfigClient;
import cloud.xuantong.integration.spring.boot.autoconfigure.XuantongConfigAutoConfiguration;
import cloud.xuantong.integration.spring.boot.autoconfigure.XuantongConfigValueProcessor;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongAutoServiceRegistration;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongDiscoveryClientFactory;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongDiscoveryClientManager;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongRegistration;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongServiceInstanceMapper;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongServiceRegistry;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongSpringDiscoveryClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.ConditionalOnBlockingDiscoveryEnabled;
import org.springframework.cloud.client.ConditionalOnDiscoveryEnabled;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationProperties;
import org.springframework.cloud.client.serviceregistry.RegistrationLifecycle;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.LinkedHashMap;

@AutoConfiguration(before = XuantongConfigAutoConfiguration.class)
@ConditionalOnClass({XuantongConfigClient.class, DiscoveryClient.class})
@ConditionalOnProperty(
        prefix = XuantongSpringCloudProperties.PREFIX,
        name = "enabled",
        matchIfMissing = true)
@EnableConfigurationProperties(XuantongSpringCloudProperties.class)
public class XuantongSpringCloudAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ClientIdentity xuantongClientIdentity(
            XuantongSpringCloudProperties properties, Environment environment) {
        String applicationName = firstNonBlank(
                properties.getApplicationName(),
                environment.getProperty("spring.application.name", "spring-application"));
        return new ClientIdentity(applicationName, properties.getClientInstanceId());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = XuantongSpringCloudProperties.PREFIX + ".config",
            name = "enabled",
            matchIfMissing = true)
    public XuantongConfigClient xuantongConfigClient(
            XuantongSpringCloudProperties properties, ClientIdentity clientIdentity) {
        return new XuantongConfigClient(
                properties.getServerAddresses(),
                properties.getNamespace(),
                properties.getGroup(),
                properties.getAccessToken(),
                clientIdentity,
                properties.configControlPlaneOptions());
    }

    @Bean
    @ConditionalOnMissingBean
    public static XuantongConfigValueProcessor xuantongConfigValueProcessor(
            ObjectProvider<XuantongConfigClient> xuantongConfigClientProvider) {
        return new XuantongConfigValueProcessor(xuantongConfigClientProvider);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnDiscoveryEnabled
    @ConditionalOnBlockingDiscoveryEnabled
    @ConditionalOnProperty(
            prefix = XuantongSpringCloudProperties.PREFIX + ".discovery",
            name = "enabled",
            matchIfMissing = true)
    static class DiscoveryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        XuantongDiscoveryClientFactory xuantongDiscoveryClientFactory(
                XuantongSpringCloudProperties properties,
                ClientIdentity clientIdentity) {
            return new XuantongDiscoveryClientFactory(properties, clientIdentity);
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        XuantongDiscoveryClientManager xuantongDiscoveryClientManager(
                XuantongDiscoveryClientFactory factory) {
            return new XuantongDiscoveryClientManager(factory);
        }

        @Bean
        @ConditionalOnMissingBean
        XuantongServiceInstanceMapper xuantongServiceInstanceMapper() {
            return new XuantongServiceInstanceMapper();
        }

        @Bean
        @ConditionalOnMissingBean(XuantongSpringDiscoveryClient.class)
        XuantongSpringDiscoveryClient xuantongDiscoveryClient(
                XuantongDiscoveryClientManager manager,
                XuantongServiceInstanceMapper mapper,
                XuantongSpringCloudProperties properties) {
            return new XuantongSpringDiscoveryClient(
                    manager, mapper, properties.getNamespace(), properties.getGroup());
        }

        @Bean
        @ConditionalOnMissingBean
        XuantongServiceRegistry xuantongServiceRegistry(
                XuantongDiscoveryClientManager manager,
                XuantongServiceInstanceMapper mapper,
                XuantongSpringCloudProperties properties) {
            return new XuantongServiceRegistry(manager, mapper, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        XuantongRegistration xuantongRegistration(
                XuantongSpringCloudProperties properties,
                ClientIdentity clientIdentity,
                Environment environment,
                ObjectProvider<InetUtils> inetUtilsProvider) {
            XuantongSpringCloudProperties.Discovery discovery = properties.getDiscovery();
            String serviceName = firstNonBlank(
                    discovery.getServiceName(),
                    properties.getApplicationName(),
                    environment.getProperty("spring.application.name", "spring-application"));
            String instanceId = firstNonBlank(
                    discovery.getInstanceId(), clientIdentity.getClientInstanceId());
            String host = firstNonBlank(
                    discovery.getIpAddress(), resolveHost(inetUtilsProvider));
            return new XuantongRegistration(
                    instanceId,
                    serviceName,
                    host,
                    discovery.getPort(),
                    discovery.isSecure(),
                    discovery.getWeight(),
                    new LinkedHashMap<>(discovery.getMetadata()));
        }

        @Bean
        @ConditionalOnMissingBean
        XuantongAutoServiceRegistration xuantongAutoServiceRegistration(
                ApplicationContext applicationContext,
                XuantongServiceRegistry serviceRegistry,
                ObjectProvider<AutoServiceRegistrationProperties> autoPropertiesProvider,
                ObjectProvider<RegistrationLifecycle<XuantongRegistration>> lifecycleProvider,
                XuantongSpringCloudProperties properties,
                XuantongRegistration registration) {
            AutoServiceRegistrationProperties autoProperties = autoPropertiesProvider
                    .getIfAvailable(AutoServiceRegistrationProperties::new);
            return new XuantongAutoServiceRegistration(
                    applicationContext,
                    serviceRegistry,
                    autoProperties,
                    properties,
                    registration,
                    lifecycleProvider.orderedStream().toList());
        }

        private static String resolveHost(ObjectProvider<InetUtils> inetUtilsProvider) {
            InetUtils inetUtils = inetUtilsProvider.getIfAvailable();
            if (inetUtils != null) {
                return inetUtils.findFirstNonLoopbackHostInfo().getIpAddress();
            }
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ignored) {
                return "127.0.0.1";
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
