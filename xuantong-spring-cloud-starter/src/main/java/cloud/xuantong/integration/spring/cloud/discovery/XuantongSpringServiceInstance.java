package cloud.xuantong.integration.spring.cloud.discovery;

import org.springframework.cloud.client.ServiceInstance;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable Spring Cloud view of one Xuantong Registry instance. */
public final class XuantongSpringServiceInstance implements ServiceInstance {
    private final String instanceId;
    private final String serviceId;
    private final String host;
    private final int port;
    private final boolean secure;
    private final String scheme;
    private final Map<String, String> metadata;

    public XuantongSpringServiceInstance(
            String instanceId,
            String serviceId,
            String host,
            int port,
            boolean secure,
            String scheme,
            Map<String, String> metadata) {
        this.instanceId = instanceId;
        this.serviceId = serviceId;
        this.host = host;
        this.port = port;
        this.secure = secure;
        this.scheme = scheme == null || scheme.isBlank()
                ? (secure ? "https" : "http")
                : scheme.trim().toLowerCase(Locale.ROOT);
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public URI getUri() {
        try {
            return new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid Xuantong service instance URI", exception);
        }
    }

    @Override
    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public String getScheme() {
        return scheme;
    }
}
