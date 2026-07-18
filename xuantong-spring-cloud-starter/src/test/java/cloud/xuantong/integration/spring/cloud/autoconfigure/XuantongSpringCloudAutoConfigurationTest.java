package cloud.xuantong.integration.spring.cloud.autoconfigure;

import cloud.xuantong.client.XuantongConfigClient;
import cloud.xuantong.integration.spring.boot.autoconfigure.XuantongConfigAutoConfiguration;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongAutoServiceRegistration;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongRegistration;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongServiceRegistry;
import cloud.xuantong.integration.spring.cloud.discovery.XuantongSpringDiscoveryClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class XuantongSpringCloudAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    XuantongSpringCloudAutoConfiguration.class,
                    XuantongConfigAutoConfiguration.class))
            .withPropertyValues(
                    "spring.application.name=order-service",
                    "spring.cloud.xuantong.config.enabled=false",
                    "spring.cloud.xuantong.discovery.register=false",
                    "spring.cloud.xuantong.discovery.port=8080");

    @Test
    void createsSpringCloudDiscoveryAndRegistryBeansWithoutConfigClient() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(XuantongConfigClient.class);
            assertThat(context).hasSingleBean(XuantongSpringDiscoveryClient.class);
            assertThat(context).hasSingleBean(XuantongServiceRegistry.class);
            assertThat(context).hasSingleBean(XuantongRegistration.class);
            assertThat(context).hasSingleBean(XuantongAutoServiceRegistration.class);
            assertThat(context.getBean(XuantongRegistration.class).getServiceId())
                    .isEqualTo("order-service");
        });
    }

    @Test
    void globalSwitchDisablesAllAutoConfiguration() {
        contextRunner.withPropertyValues("spring.cloud.xuantong.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(XuantongSpringDiscoveryClient.class);
                    assertThat(context).doesNotHaveBean(XuantongServiceRegistry.class);
                });
    }
}
