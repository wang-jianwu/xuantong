package cloud.xuantong.integration.spring.cloud.config;

import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.ConfigClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.PropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuantongConfigDataLoaderTest {

    @Test
    void parsesYamlPropertiesJsonAndScalarContent() throws Exception {
        assertEquals("玄同", property(
                XuantongConfigDataLoader.parse("application.yml", "app:\n  name: 玄同\n"),
                "app.name"));
        assertEquals("8080", property(
                XuantongConfigDataLoader.parse("application.properties", "server.port=8080\n"),
                "server.port").toString());
        List<PropertySource<?>> json = XuantongConfigDataLoader.parse(
                "application.json", "{\"app\":{\"name\":\"xuantong\"},\"zones\":[\"a\",\"b\"]}");
        assertEquals("xuantong", property(json, "app.name"));
        assertEquals("b", property(json, "zones[1]"));
        assertEquals("hello", property(
                XuantongConfigDataLoader.parse("demo.message", "hello"),
                "demo.message"));
    }

    @Test
    void optionalImportDoesNotBlockStartupWhenControlPlaneIsUnavailable() throws Exception {
        XuantongConfigDataLoader.ConfigClient client = new XuantongConfigDataLoader.ConfigClient() {
            @Override
            public String get(String dataId) {
                throw new IllegalStateException("offline");
            }

            @Override
            public void close() {
            }
        };
        XuantongConfigDataLoader loader = new XuantongConfigDataLoader(settings -> client);
        XuantongConfigDataResource resource = new XuantongConfigDataResource(
                "application.yml",
                true,
                false,
                new XuantongConfigDataSettings(
                        true,
                        List.of("127.0.0.1:8090"),
                        "public",
                        "DEFAULT_GROUP",
                        "",
                        "demo",
                        null,
                        ControlPlaneOptions.defaults(),
                        ConfigClientOptions.defaults()));

        ConfigData result = loader.load(null, resource);

        assertTrue(result.getPropertySources().isEmpty());
    }

    @Test
    void reusesOneBootstrapClientForResourcesWithTheSameScope() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        XuantongConfigDataLoader loader = new XuantongConfigDataLoader(settings -> {
            creates.incrementAndGet();
            return new XuantongConfigDataLoader.ConfigClient() {
                @Override
                public String get(String dataId) {
                    return "value: " + dataId;
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        });
        XuantongConfigDataSettings settings = new XuantongConfigDataSettings(
                true,
                List.of("127.0.0.1:8090"),
                "public",
                "DEFAULT_GROUP",
                "",
                "demo",
                null,
                ControlPlaneOptions.defaults(),
                ConfigClientOptions.defaults());
        DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext();
        ConfigDataLoaderContext loaderContext = () -> bootstrapContext;

        loader.load(loaderContext, new XuantongConfigDataResource(
                "application.yml", false, false, settings));
        loader.load(loaderContext, new XuantongConfigDataResource(
                "application-prod.yml", false, true, settings));

        assertEquals(1, creates.get());
        assertEquals(0, closes.get());

        bootstrapContext.close(new GenericApplicationContext());
        assertEquals(1, closes.get());
    }

    private Object property(List<PropertySource<?>> sources, String name) {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
