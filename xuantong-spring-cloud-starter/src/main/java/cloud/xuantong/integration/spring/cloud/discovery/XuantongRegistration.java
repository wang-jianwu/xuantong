package cloud.xuantong.integration.spring.cloud.discovery;

import org.springframework.cloud.client.serviceregistry.Registration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Spring Cloud registration backed by an authoritative Xuantong Registry lease. */
public class XuantongRegistration implements Registration {
    private final String instanceId;
    private final String serviceId;
    private final String host;
    private final boolean secure;
    private final double weight;
    private final Map<String, String> metadata;
    private volatile int port;
    private volatile String status = "UP";

    public XuantongRegistration(
            String instanceId,
            String serviceId,
            String host,
            int port,
            boolean secure,
            double weight,
            Map<String, String> metadata) {
        this.instanceId = requireText("instanceId", instanceId);
        this.serviceId = requireText("serviceId", serviceId);
        this.host = requireText("host", host);
        this.port = port;
        this.secure = secure;
        if (!Double.isFinite(weight) || weight <= 0D) {
            throw new IllegalArgumentException("weight must be a positive finite number");
        }
        this.weight = weight;
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

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public URI getUri() {
        String scheme = getScheme();
        try {
            return new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid registration URI", exception);
        }
    }

    @Override
    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public String getScheme() {
        String configured = metadata.get("scheme");
        return configured == null || configured.isBlank()
                ? (secure ? "https" : "http")
                : configured.trim().toLowerCase(Locale.ROOT);
    }

    public double getWeight() {
        return weight;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = requireText("status", status).toUpperCase(Locale.ROOT);
    }

    private String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
