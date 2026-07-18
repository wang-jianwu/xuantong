package cloud.xuantong.integration.spring.cloud.config;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.XuantongConfigClient;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads Xuantong content into Spring Boot's ConfigData environment. */
public class XuantongConfigDataLoader
        implements ConfigDataLoader<XuantongConfigDataResource> {
    private final ClientFactory clientFactory;

    public XuantongConfigDataLoader() {
        this(settings -> new ConfigClientAdapter(new XuantongConfigClient(
                settings.serverAddresses(),
                settings.namespace(),
                settings.group(),
                settings.accessToken(),
                new ClientIdentity(settings.applicationName(), settings.clientInstanceId()),
                settings.controlPlaneOptions())));
    }

    XuantongConfigDataLoader(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public ConfigData load(
            ConfigDataLoaderContext context, XuantongConfigDataResource resource)
            throws IOException {
        XuantongConfigDataSettings settings = resource.getSettings();
        if (!settings.enabled()) {
            return ConfigData.EMPTY;
        }

        String content;
        try (ConfigClient client = clientFactory.create(settings)) {
            content = client.get(resource.getDataId());
        } catch (RuntimeException exception) {
            if (resource.isOptional()) {
                return ConfigData.EMPTY;
            }
            throw new ConfigDataResourceNotFoundException(resource, exception);
        }

        if (content == null) {
            if (resource.isOptional()) {
                return ConfigData.EMPTY;
            }
            throw new ConfigDataResourceNotFoundException(resource);
        }

        List<PropertySource<?>> propertySources = parse(
                resource.getDataId(), content);
        List<ConfigData.Option> options = new ArrayList<>();
        options.add(ConfigData.Option.IGNORE_IMPORTS);
        if (resource.isProfileSpecific()) {
            options.add(ConfigData.Option.PROFILE_SPECIFIC);
        }
        return new ConfigData(
                propertySources, options.toArray(ConfigData.Option[]::new));
    }

    static List<PropertySource<?>> parse(String dataId, String content) throws IOException {
        String normalized = dataId.toLowerCase(Locale.ROOT);
        Resource resource = new NamedByteArrayResource(dataId, content);
        String propertySourceName = "xuantong-config[" + dataId + "]";
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml")) {
            return new YamlPropertySourceLoader().load(propertySourceName, resource);
        }
        if (normalized.endsWith(".properties")) {
            return new PropertiesPropertySourceLoader().load(propertySourceName, resource);
        }
        if (normalized.endsWith(".json")) {
            Map<String, Object> flattened = new LinkedHashMap<>();
            flatten("", JsonParserFactory.getJsonParser().parseMap(content), flattened);
            return List.of(new MapPropertySource(propertySourceName, flattened));
        }
        return List.of(new MapPropertySource(
                propertySourceName, Map.of(dataId, content)));
    }

    private static void flatten(
            String prefix, Object value, Map<String, Object> target) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                flatten(prefix.isEmpty() ? key : prefix + "." + key,
                        entry.getValue(), target);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flatten(prefix + "[" + index + "]", list.get(index), target);
            }
            return;
        }
        if (!prefix.isEmpty()) {
            target.put(prefix, value);
        }
    }

    @FunctionalInterface
    interface ClientFactory {
        ConfigClient create(XuantongConfigDataSettings settings);
    }

    interface ConfigClient extends AutoCloseable {
        String get(String dataId);

        @Override
        void close();
    }

    private record ConfigClientAdapter(XuantongConfigClient delegate) implements ConfigClient {
        @Override
        public String get(String dataId) {
            return delegate.get(dataId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(String filename, String content) {
            super(content.getBytes(StandardCharsets.UTF_8), filename);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
