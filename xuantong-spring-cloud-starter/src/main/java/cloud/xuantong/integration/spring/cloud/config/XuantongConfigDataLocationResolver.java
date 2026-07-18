package cloud.xuantong.integration.spring.cloud.config;

import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.context.properties.bind.Bindable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves {@code spring.config.import=xuantong:<dataId>}. */
public class XuantongConfigDataLocationResolver
        implements ConfigDataLocationResolver<XuantongConfigDataResource> {
    static final String PREFIX = "xuantong:";

    @Override
    public boolean isResolvable(
            ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<XuantongConfigDataResource> resolve(
            ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        String dataId = requireDataId(location);
        XuantongConfigDataSettings settings = settings(context);
        if (!settings.enabled() && !location.isOptional()) {
            throw new ConfigDataLocationNotFoundException(
                    location,
                    XuantongSpringCloudProperties.PREFIX
                            + ".enabled and .config.enabled must be true",
                    null);
        }
        return List.of(new XuantongConfigDataResource(
                dataId, location.isOptional(), false, settings));
    }

    @Override
    public List<XuantongConfigDataResource> resolveProfileSpecific(
            ConfigDataLocationResolverContext context,
            ConfigDataLocation location,
            Profiles profiles) {
        String dataId = requireDataId(location);
        int extensionIndex = extensionIndex(dataId);
        if (extensionIndex < 0) {
            return List.of();
        }
        XuantongConfigDataSettings settings = settings(context);
        List<XuantongConfigDataResource> resources = new ArrayList<>();
        for (String profile : profiles.getAccepted()) {
            resources.add(new XuantongConfigDataResource(
                    dataId.substring(0, extensionIndex) + "-" + profile
                            + dataId.substring(extensionIndex),
                    true,
                    true,
                    settings));
        }
        return resources;
    }

    private XuantongConfigDataSettings settings(ConfigDataLocationResolverContext context) {
        XuantongSpringCloudProperties properties = context.getBinder()
                .bind(XuantongSpringCloudProperties.PREFIX,
                        Bindable.of(XuantongSpringCloudProperties.class))
                .orElseGet(XuantongSpringCloudProperties::new);
        String applicationName = firstNonBlank(
                properties.getApplicationName(),
                context.getBinder().bind("spring.application.name", String.class)
                        .orElse("spring-application"));
        return XuantongConfigDataSettings.from(properties, applicationName);
    }

    private String requireDataId(ConfigDataLocation location) {
        String dataId = location.getNonPrefixedValue(PREFIX).trim();
        if (dataId.isEmpty()) {
            throw new ConfigDataLocationNotFoundException(
                    location, "Xuantong dataId must not be blank", null);
        }
        return dataId;
    }

    private int extensionIndex(String dataId) {
        String normalized = dataId.toLowerCase(Locale.ROOT);
        for (String extension : List.of(".properties", ".yaml", ".yml", ".json")) {
            if (normalized.endsWith(extension)) {
                return dataId.length() - extension.length();
            }
        }
        return -1;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
