package cloud.xuantong.client.core;

import cloud.xuantong.client.cache.ConfigCacheManager;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ConfigListenerManager;
import cloud.xuantong.client.model.ConfigChangeEvent;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ControlPlaneEvent;
import cloud.xuantong.client.serializer.Serializer;
import cloud.xuantong.client.transport.ConfigTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConfigCore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCore.class);
    private static final long RECONCILE_INTERVAL_MS = 30_000L;

    private final String namespace;
    private final String group;
    private final ConfigTransport transport;
    private final ConfigCacheManager cacheManager;
    private final ConfigListenerManager listenerManager = new ConfigListenerManager();
    private final Serializer serializer = Serializer.defaultSerializer();
    private final Map<String, Long> revisions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconcileExecutor;
    private volatile boolean initialized;

    public ConfigCore(List<String> serverAddresses,
                      String namespace,
                      String group,
                      String accessToken,
                      ConfigTransport transport) {
        this.namespace = requireName("namespace", namespace);
        this.group = requireName("group", group);
        this.transport = transport;
        this.cacheManager = new ConfigCacheManager(this.namespace, this.group);
        this.reconcileExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-config-reconciler");
            thread.setDaemon(true);
            return thread;
        });
        initialize(serverAddresses, accessToken);
    }

    private void initialize(List<String> serverAddresses, String accessToken) {
        try {
            transport.setOnReconnect(this::reloadKnownConfigs);
            transport.connect(serverAddresses, namespace, group,
                    accessToken == null ? "" : accessToken, this::handleControlPlaneEvent);
            reloadKnownConfigs();
            initialized = true;
            reconcileExecutor.scheduleWithFixedDelay(
                    this::reloadKnownConfigsSafely,
                    RECONCILE_INTERVAL_MS,
                    RECONCILE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            logger.info("Xuantong config client 2.0 initialized: namespace={}, group={}", namespace, group);
        } catch (Exception e) {
            transport.close();
            reconcileExecutor.shutdownNow();
            cacheManager.shutdown();
            listenerManager.shutdown();
            throw new XuantongException("Failed to initialize Xuantong config client 2.0", e);
        }
    }

    public String get(String dataId, String defaultValue) {
        ensureInitialized();
        String cached = cacheManager.get(dataId);
        if (cached != null) return cached;

        ConfigSnapshot snapshot = transport.fetch(dataId);
        if (snapshot == null) return defaultValue;
        applySnapshot(snapshot, false);
        return snapshot.getContent();
    }

    private void handleControlPlaneEvent(String eventJson) {
        try {
            ControlPlaneEvent event = serializer.deserialize(eventJson, ControlPlaneEvent.class);
            if (event == null || event.getResourceName() == null || event.getPayload() == null) return;
            if (!namespace.equals(event.getNamespaceId()) || !group.equals(event.getGroupName())) return;

            long revision = event.getRevision() == null ? 0L : event.getRevision();
            long localRevision = revisions.getOrDefault(event.getResourceName(), 0L);
            if (revision <= localRevision) {
                logger.debug("Ignoring duplicate/out-of-order event: dataId={}, revision={}, local={}",
                        event.getResourceName(), revision, localRevision);
                return;
            }

            Object contentValue = event.getPayload().get("content");
            String content = contentValue == null ? null : String.valueOf(contentValue);
            ConfigSnapshot snapshot = new ConfigSnapshot(
                    event.getResourceName(), content, revision,
                    stringValue(event.getPayload().get("checksum")),
                    stringValue(event.getPayload().get("contentType")));
            applySnapshot(snapshot, true);
        } catch (Exception e) {
            logger.warn("Failed to process config-v2 event", e);
        }
    }

    private void applySnapshot(ConfigSnapshot snapshot, boolean notify) {
        String oldValue = cacheManager.get(snapshot.getDataId());
        if (snapshot.getContent() == null) {
            cacheManager.remove(snapshot.getDataId());
        } else {
            cacheManager.batchUpdate(java.util.Collections.singletonMap(
                    snapshot.getDataId(), snapshot.getContent()));
        }
        revisions.put(snapshot.getDataId(), snapshot.getRevision());
        if (notify && !java.util.Objects.equals(oldValue, snapshot.getContent())) {
            listenerManager.fireEvent(new ConfigChangeEvent(
                    namespace, group, snapshot.getDataId(), snapshot.getContent(), snapshot.getRevision()));
        }
    }

    private void reloadKnownConfigs() {
        for (String dataId : cacheManager.getAll().keySet()) {
            ConfigSnapshot snapshot = transport.fetch(dataId);
            if (snapshot != null && snapshot.getRevision() > revisions.getOrDefault(dataId, 0L)) {
                applySnapshot(snapshot, true);
            }
        }
    }

    private void reloadKnownConfigsSafely() {
        if (!initialized) {
            return;
        }
        try {
            reloadKnownConfigs();
        } catch (Exception e) {
            logger.warn("Periodic config reconciliation failed; it will retry", e);
        }
    }

    public void addConfigListener(String dataId, ConfigListener listener) {
        listenerManager.addListener(dataId, listener);
    }

    public void removeConfigListener(String dataId, ConfigListener listener) {
        listenerManager.removeListener(dataId, listener);
    }

    public String getNamespace() { return namespace; }
    public String getGroup() { return group; }
    public boolean isInitialized() { return initialized; }

    @Override
    public void close() {
        initialized = false;
        reconcileExecutor.shutdownNow();
        transport.close();
        cacheManager.shutdown();
        listenerManager.shutdown();
    }

    private void ensureInitialized() {
        if (!initialized) throw new IllegalStateException("Config client is not initialized");
    }

    private String requireName(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
