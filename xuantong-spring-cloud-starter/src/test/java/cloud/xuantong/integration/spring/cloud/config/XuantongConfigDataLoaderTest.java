package cloud.xuantong.integration.spring.cloud.config;

import cloud.xuantong.client.ControlPlaneOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.core.env.PropertySource;

import java.util.List;

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
                        ControlPlaneOptions.defaults()));

        ConfigData result = loader.load(null, resource);

        assertTrue(result.getPropertySources().isEmpty());
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
