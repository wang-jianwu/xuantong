package cloud.xuantong.integration.solon.cloud;

import cloud.xuantong.client.XuantongDiscoveryClient;
import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.metrics.LeaseRenewalMetricsSnapshot;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.serializer.Serializer;
import cloud.xuantong.client.transport.impl.SharedDiscoveryConnection;
import org.noear.solon.cloud.CloudDiscoveryHandler;
import org.noear.solon.cloud.CloudProps;
import org.noear.solon.Solon;
import org.noear.solon.cloud.exception.CloudException;
import org.noear.solon.cloud.model.Discovery;
import org.noear.solon.cloud.model.Instance;
import org.noear.solon.cloud.service.CloudDiscoveryObserverEntity;
import org.noear.solon.cloud.service.CloudDiscoveryService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class XuantongCloudDiscoveryService implements CloudDiscoveryService, AutoCloseable {
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    private static final String TAGS_META = "solon.tags";

    private final List<String> serverAddresses;
    private final String namespace;
    private final String accessToken;
    private final long heartbeatIntervalMs;
    private final ClientIdentity clientIdentity;
    private final ControlPlaneOptions controlPlaneOptions;
    private final SharedDiscoveryConnection sharedConnection;
    private final Serializer serializer = Serializer.defaultSerializer();
    private final Map<String, XuantongDiscoveryClient> discoveryClients = new ConcurrentHashMap<>();
    private final Map<String, LocalRegistration> registrationClients =
            new ConcurrentHashMap<>();

    public XuantongCloudDiscoveryService(CloudProps cloudProps) {
        this.namespace = normalizeNamespace(cloudProps.getNamespace());
        this.serverAddresses = parseServerAddresses(cloudProps.getDiscoveryServer());
        if (serverAddresses.isEmpty()) {
            throw new CloudException("solon.cloud.xuantong.discovery.server 不能为空");
        }
        String token = cloudProps.getToken();
        this.accessToken = token == null ? "" : token.trim();
        this.heartbeatIntervalMs = parseDurationMillis(
                cloudProps.getDiscoveryHealthCheckInterval("10s"));
        this.clientIdentity = new ClientIdentity(Solon.cfg().appName(), null);
        ControlPlaneOptions defaults = ControlPlaneOptions.registryDefaults();
        this.controlPlaneOptions = new ControlPlaneOptions(
                Solon.cfg().get("solon.cloud.xuantong.tenant", defaults.tenant()),
                Solon.cfg().get("solon.cloud.xuantong.discovery.stateGroupId",
                        defaults.stateGroupId()),
                Solon.cfg().get("solon.cloud.xuantong.clusterId", defaults.clusterId()),
                Solon.cfg().getLong("solon.cloud.xuantong.transportGeneration",
                        defaults.transportGeneration()),
                Solon.cfg().get("solon.cloud.xuantong.transportPool",
                        defaults.transportPool()),
                defaults.connectTimeoutMs(),
                defaults.requestTimeoutMs(),
                defaults.operationTimeoutMs(),
                defaults.closingTimeoutMs(),
                SolonCloudTlsOptions.load());
        long leaseTtlMs;
        try {
            leaseTtlMs = Math.max(
                    30_000L, Math.multiplyExact(heartbeatIntervalMs, 3L));
        } catch (ArithmeticException e) {
            throw new CloudException(
                    "discovery.healthCheckInterval 数值过大", e);
        }
        this.sharedConnection = new SharedDiscoveryConnection(
                clientIdentity, controlPlaneOptions, leaseTtlMs);
    }

    @Override
    public void register(String group, Instance instance) {
        registerState(group, instance, true);
    }

    @Override
    public synchronized void registerState(
            String group, Instance instance, boolean health) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        String normalizedGroup = normalizeGroup(group);
        String serviceName = requireName("service", instance.service());
        String instanceId = instanceId(instance);
        String key = registrationKey(normalizedGroup, serviceName, instanceId);
        ServiceInstance registration = toXuantongInstance(instance, instanceId, health);
        LocalRegistration existing = registrationClients.get(key);
        if (existing != null && sameRegistration(existing.definition(), registration)) {
            return;
        }
        if (existing != null) {
            registrationClients.remove(key, existing);
            existing.client().close();
        }
        XuantongDiscoveryClient client = newClient(normalizedGroup, serviceName);
        try {
            client.register(registration);
            registrationClients.put(
                    key, new LocalRegistration(client, copyRegistration(registration)));
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    @Override
    public synchronized void deregister(String group, Instance instance) {
        if (instance == null) {
            return;
        }
        String normalizedGroup = normalizeGroup(group);
        String instanceId = instanceId(instance);
        String key = registrationKey(normalizedGroup, instance.service(), instanceId);
        LocalRegistration registration = registrationClients.remove(key);
        if (registration != null) {
            registration.client().close();
        }
    }

    @Override
    public Discovery find(String group, String service) {
        String normalizedGroup = normalizeGroup(group);
        String serviceName = requireName("service", service);
        return toDiscovery(normalizedGroup, serviceName,
                discoveryClient(normalizedGroup, serviceName).getInstances());
    }

    @Override
    public Collection<String> findServices(String group) {
        String normalizedGroup = normalizeGroup(group);
        return sharedConnection.fetchServices(
                serverAddresses, namespace, normalizedGroup, accessToken);
    }

    @Override
    public void attention(String group, String service, CloudDiscoveryHandler observer) {
        String normalizedGroup = normalizeGroup(group);
        String serviceName = requireName("service", service);
        CloudDiscoveryObserverEntity entity = new CloudDiscoveryObserverEntity(
                normalizedGroup, serviceName, observer);
        XuantongDiscoveryClient client = discoveryClient(normalizedGroup, serviceName);
        client.addListener(event -> entity.handle(toDiscovery(
                normalizedGroup, serviceName, event.getAvailableInstances())));
    }

    private XuantongDiscoveryClient discoveryClient(String group, String serviceName) {
        String key = group + "\u0000" + serviceName;
        return discoveryClients.computeIfAbsent(key, ignored -> newClient(group, serviceName));
    }

    private XuantongDiscoveryClient newClient(String group, String serviceName) {
        return new XuantongDiscoveryClient(
                serverAddresses, namespace, group, serviceName, accessToken,
                heartbeatIntervalMs, sharedConnection.newServiceTransport());
    }

    /** Returns fixed-memory renewal telemetry for locally registered leases. */
    public List<LeaseRenewalMetricsSnapshot> leaseRenewalMetrics() {
        return registrationClients.values().stream()
                .map(LocalRegistration::client)
                .map(XuantongDiscoveryClient::getLeaseRenewalMetrics)
                .toList();
    }

    private boolean sameRegistration(ServiceInstance left, ServiceInstance right) {
        return Objects.equals(left.getInstanceId(), right.getInstanceId())
                && Objects.equals(left.getIp(), right.getIp())
                && Objects.equals(left.getPort(), right.getPort())
                && Objects.equals(left.getWeight(), right.getWeight())
                && Objects.equals(left.getEnabled(), right.getEnabled())
                && Objects.equals(left.getMetadata(), right.getMetadata());
    }

    private ServiceInstance copyRegistration(ServiceInstance source) {
        ServiceInstance target = new ServiceInstance();
        target.setInstanceId(source.getInstanceId());
        target.setIp(source.getIp());
        target.setPort(source.getPort());
        target.setWeight(source.getWeight());
        target.setEnabled(source.getEnabled());
        target.setMetadata(source.getMetadata());
        return target;
    }

    private ServiceInstance toXuantongInstance(
            Instance source, String instanceId, boolean health) {
        ServiceInstance target = new ServiceInstance();
        target.setInstanceId(instanceId);
        target.setIp(source.host());
        target.setPort(source.port());
        target.setWeight(source.weight());
        target.setEnabled(health);

        Map<String, String> metadata = new LinkedHashMap<>(source.meta());
        if (source.protocol() != null) {
            metadata.put("protocol", source.protocol());
        }
        if (source.tags() != null && !source.tags().isEmpty()) {
            metadata.put(TAGS_META, String.join(",", source.tags()));
        }
        target.setMetadata(serializer.serialize(metadata));
        return target;
    }

    private Discovery toDiscovery(
            String group, String serviceName, List<ServiceInstance> sourceInstances) {
        Discovery discovery = new Discovery(group, serviceName);
        for (ServiceInstance source : sourceInstances) {
            Instance target = new Instance(serviceName, source.getIp(), source.getPort())
                    .weight(source.getWeight() == null ? 1D : source.getWeight());
            Map<String, String> metadata = deserializeMetadata(source.getMetadata());
            String tags = metadata.remove(TAGS_META);
            target.metaPutAll(metadata);
            if (tags != null && !tags.trim().isEmpty()) {
                target.tagsAddAll(java.util.Arrays.asList(tags.split(",")));
            }
            discovery.instanceAdd(target);
        }
        return discovery;
    }

    private Map<String, String> deserializeMetadata(String metadata) {
        if (metadata == null || metadata.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> values = serializer.deserializeMap(
                metadata, String.class, String.class);
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private String instanceId(Instance instance) {
        return UUID.nameUUIDFromBytes(
                instance.serviceAndAddress().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String registrationKey(String group, String serviceName, String instanceId) {
        return group + "\u0000" + serviceName + "\u0000" + instanceId;
    }

    private String normalizeNamespace(String value) {
        return requireName("namespace", value == null || value.trim().isEmpty() ? "public" : value);
    }

    private String normalizeGroup(String value) {
        return requireName("group", value == null || value.trim().isEmpty() ? DEFAULT_GROUP : value);
    }

    private String requireName(String field, String value) {
        if (value == null || !value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value.trim();
    }

    private List<String> parseServerAddresses(String server) {
        if (server == null) {
            return Collections.emptyList();
        }
        List<String> addresses = new ArrayList<>();
        for (String address : server.split(",")) {
            String trimmed = address.trim();
            if (!trimmed.isEmpty()) {
                addresses.add(trimmed);
            }
        }
        return addresses;
    }

    private long parseDurationMillis(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 10_000L;
        }
        String normalized = value.trim().toLowerCase();
        try {
            if (normalized.endsWith("ms")) {
                return positive(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
            }
            if (normalized.endsWith("s")) {
                return positive(Long.parseLong(normalized.substring(0, normalized.length() - 1)) * 1000L);
            }
            if (normalized.endsWith("m")) {
                return positive(Long.parseLong(normalized.substring(0, normalized.length() - 1)) * 60_000L);
            }
            return positive(Long.parseLong(normalized));
        } catch (NumberFormatException e) {
            throw new CloudException("discovery.healthCheckInterval 格式错误: " + value);
        }
    }

    private long positive(long value) {
        if (value <= 0L) {
            throw new CloudException("discovery.healthCheckInterval 必须大于 0");
        }
        return value;
    }

    @Override
    public synchronized void close() {
        for (LocalRegistration registration : registrationClients.values()) {
            registration.client().close();
        }
        for (XuantongDiscoveryClient client : discoveryClients.values()) {
            client.close();
        }
        registrationClients.clear();
        discoveryClients.clear();
        sharedConnection.close();
    }

    private record LocalRegistration(
            XuantongDiscoveryClient client, ServiceInstance definition) {
    }
}
