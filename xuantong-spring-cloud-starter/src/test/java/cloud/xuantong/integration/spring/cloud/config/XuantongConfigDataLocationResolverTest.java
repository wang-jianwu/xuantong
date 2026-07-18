package cloud.xuantong.integration.spring.cloud.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class XuantongConfigDataLocationResolverTest {

    @Test
    void resolvesLocationUsingSpringCloudProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "spring.cloud.xuantong.server-addresses[0]", "10.0.0.1:8090",
                "spring.cloud.xuantong.namespace", "prod",
                "spring.cloud.xuantong.group", "PAYMENT",
                "spring.application.name", "order-service"));
        ConfigDataLocationResolverContext context = new ConfigDataLocationResolverContext() {
            @Override
            public Binder getBinder() {
                return new Binder(source);
            }

            @Override
            public ConfigDataResource getParent() {
                return null;
            }

            @Override
            public ConfigurableBootstrapContext getBootstrapContext() {
                return null;
            }
        };

        XuantongConfigDataLocationResolver resolver = new XuantongConfigDataLocationResolver();
        List<XuantongConfigDataResource> resources = resolver.resolve(
                context, ConfigDataLocation.of("xuantong:application.yml"));

        assertEquals(1, resources.size());
        XuantongConfigDataResource resource = resources.getFirst();
        assertEquals("application.yml", resource.getDataId());
        assertFalse(resource.isOptional());
        assertEquals(List.of("10.0.0.1:8090"), resource.getSettings().serverAddresses());
        assertEquals("prod", resource.getSettings().namespace());
        assertEquals("PAYMENT", resource.getSettings().group());
        assertEquals("order-service", resource.getSettings().applicationName());
    }
}
