package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.serializer.Serializer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the authoritative Registry State model to Spring Cloud instances. */
public class XuantongServiceInstanceMapper {
    private final Serializer serializer = Serializer.defaultSerializer();

    public org.springframework.cloud.client.ServiceInstance toSpring(
            cloud.xuantong.client.model.ServiceInstance source) {
        Map<String, String> metadata = metadata(source.getMetadata());
        metadata.putIfAbsent("xuantong.namespace", nullToEmpty(source.getNamespaceId()));
        metadata.putIfAbsent("xuantong.group", nullToEmpty(source.getGroupName()));
        if (source.getWeight() != null) {
            String weight = Double.toString(source.getWeight());
            metadata.putIfAbsent("weight", weight);
            metadata.putIfAbsent("xuantong.weight", weight);
        }
        if (source.getLeaseId() != null) {
            metadata.putIfAbsent("xuantong.lease-id", source.getLeaseId());
        }

        String scheme = firstNonBlank(metadata.get("scheme"), metadata.get("protocol"));
        boolean secure = Boolean.parseBoolean(metadata.getOrDefault("secure", "false"))
                || "https".equalsIgnoreCase(scheme);
        return new XuantongSpringServiceInstance(
                source.getInstanceId(),
                source.getServiceName(),
                source.getIp(),
                source.getPort() == null ? 0 : source.getPort(),
                secure,
                scheme,
                metadata);
    }

    public cloud.xuantong.client.model.ServiceInstance toXuantong(
            XuantongRegistration registration,
            String namespace,
            String group) {
        cloud.xuantong.client.model.ServiceInstance target =
                new cloud.xuantong.client.model.ServiceInstance();
        target.setNamespaceId(namespace);
        target.setGroupName(group);
        target.setServiceName(registration.getServiceId());
        target.setInstanceId(registration.getInstanceId());
        target.setIp(registration.getHost());
        target.setPort(registration.getPort());
        target.setWeight(registration.getWeight());
        target.setHealthy(true);
        target.setEnabled("UP".equalsIgnoreCase(registration.getStatus()));

        Map<String, String> metadata = new LinkedHashMap<>(registration.getMetadata());
        metadata.putIfAbsent("secure", Boolean.toString(registration.isSecure()));
        metadata.putIfAbsent("scheme", registration.getScheme());
        metadata.putIfAbsent("weight", Double.toString(registration.getWeight()));
        target.setMetadata(serializer.serialize(metadata));
        return target;
    }

    private Map<String, String> metadata(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed = serializer.deserializeMap(
                    value, String.class, String.class);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException ignored) {
            Map<String, String> fallback = new LinkedHashMap<>();
            fallback.put("xuantong.metadata.raw", value);
            return fallback;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
