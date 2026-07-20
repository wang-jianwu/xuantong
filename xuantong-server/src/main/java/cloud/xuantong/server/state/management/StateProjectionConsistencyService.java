package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.repository.ConfigReleaseRepository;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import cloud.xuantong.config.management.repository.ConfigRolloutRepository;
import cloud.xuantong.config.state.ConfigContentDigest;
import cloud.xuantong.config.state.ConfigDecisionState;
import cloud.xuantong.config.state.ConfigProjectionEntry;
import cloud.xuantong.config.state.ConfigProjectionSnapshot;
import cloud.xuantong.config.state.ConfigProjectionSnapshotRequest;
import cloud.xuantong.config.state.ConfigRolloutDigest;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.repository.ServiceDefinitionRepository;
import cloud.xuantong.discovery.management.service.ServiceDefinitionService;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.ServiceLifecycle;
import cloud.xuantong.registry.state.ServiceLifecycleSnapshot;
import cloud.xuantong.registry.state.ServiceLifecycleSnapshotRequest;
import cloud.xuantong.registry.state.ServiceLifecycleStatus;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.resource.model.ServiceKey;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateClient;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Compares linearizable Config/Registry State with the restored SQL projection.
 *
 * <p>This service is intentionally read-only. It does not guess missing history
 * or overwrite either authoritative State or management data. Recovery is
 * accepted only after the report is complete and contains no issue.</p>
 */
@Component
public final class StateProjectionConsistencyService {
    private static final int MAX_ISSUES = 1_000;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_SCAN_ATTEMPTS = 3;

    @Inject
    private ControlStatePlaneRuntime runtime;
    @Inject
    private ConfigStatePlaneProperties configProperties;
    @Inject
    private RegistryStatePlaneProperties registryProperties;
    @Inject
    private ConfigResourceRepository configResources;
    @Inject
    private ConfigReleaseRepository configReleases;
    @Inject
    private ConfigRolloutRepository configRollouts;
    @Inject
    private ServiceDefinitionRepository serviceDefinitions;
    private StateClient directStateClient;

    public StateProjectionConsistencyService() {
    }

    StateProjectionConsistencyService(
            StateClient stateClient,
            ConfigStatePlaneProperties configProperties,
            RegistryStatePlaneProperties registryProperties,
            ConfigResourceRepository configResources,
            ConfigReleaseRepository configReleases,
            ConfigRolloutRepository configRollouts,
            ServiceDefinitionRepository serviceDefinitions) {
        this.directStateClient = Objects.requireNonNull(stateClient, "stateClient");
        this.configProperties = Objects.requireNonNull(configProperties, "configProperties");
        this.registryProperties = Objects.requireNonNull(
                registryProperties, "registryProperties");
        this.configResources = Objects.requireNonNull(configResources, "configResources");
        this.configReleases = Objects.requireNonNull(configReleases, "configReleases");
        this.configRollouts = Objects.requireNonNull(configRollouts, "configRollouts");
        this.serviceDefinitions = Objects.requireNonNull(
                serviceDefinitions, "serviceDefinitions");
    }

    public ConsistencyReport check() {
        requireAvailable();
        ConfigProjectionSnapshot config = configSnapshot();
        RegistryScan registryScan = registrySnapshot();
        ServiceLifecycleSnapshot registry = registryScan.snapshot();
        IssueCollector issues = new IssueCollector(MAX_ISSUES);

        List<ConfigResource> sqlConfigs = configResources.findAll();
        Map<ConfigResourceKey, ConfigResource> sqlConfigByKey = new LinkedHashMap<>();
        for (ConfigResource resource : sqlConfigs) {
            sqlConfigByKey.put(ConfigResourceKey.of(
                    resource.getNamespaceId(), resource.getGroupName(), resource.getDataId()),
                    resource);
        }
        for (ConfigProjectionEntry entry : config.entries()) {
            checkConfigEntry(entry, sqlConfigByKey.remove(toResourceKey(entry)), issues);
        }
        for (Map.Entry<ConfigResourceKey, ConfigResource> extra : sqlConfigByKey.entrySet()) {
            ConfigResource resource = extra.getValue();
            if (revision(resource) > 0
                    || !"DRAFT".equals(resource.getLifecycleStatus())) {
                issues.add("CONFIG", "CONFIG_STATE_MISSING",
                        extra.getKey().canonicalName(),
                        "SQL contains a published projection with no Config State decision");
            }
        }

        List<ServiceDefinition> sqlServices = serviceDefinitions.findAll();
        Map<ServiceKey, ServiceDefinition> sqlServiceByKey = new LinkedHashMap<>();
        for (ServiceDefinition service : sqlServices) {
            sqlServiceByKey.put(ServiceKey.of(
                    service.getNamespaceId(), service.getGroupName(),
                    service.getServiceName()), service);
        }
        for (ServiceLifecycle lifecycle : registry.services()) {
            checkServiceLifecycle(lifecycle, sqlServiceByKey.remove(toResourceKey(lifecycle)),
                    issues);
        }
        for (Map.Entry<ServiceKey, ServiceDefinition> extra : sqlServiceByKey.entrySet()) {
            ServiceDefinition service = extra.getValue();
            String state = service.getLifecycleState();
            String code = ServiceDefinitionService.LIFECYCLE_ACTIVATING.equals(state)
                    || ServiceDefinitionService.LIFECYCLE_DELETING.equals(state)
                    ? "REGISTRY_SQL_LIFECYCLE_PENDING" : "REGISTRY_STATE_MISSING";
            issues.add("REGISTRY", code, extra.getKey().canonicalName(),
                    "SQL lifecycle=" + state + " has no Registry State lifecycle");
        }

        return new ConsistencyReport(
                issues.isEmpty(),
                !issues.truncated(),
                System.currentTimeMillis(),
                new ConfigSummary(
                        config.eventRevision(),
                        config.compactionRevision(),
                        config.entries().size(),
                        sqlConfigs.size()),
                new RegistrySummary(
                        registry.registryRevision(),
                        registryScan.appliedIndex(),
                        registry.serverTimeEpochMs(),
                        registry.services().size(),
                        sqlServices.size()),
                issues.values(),
                issues.truncated());
    }

    private void checkConfigEntry(
            ConfigProjectionEntry entry,
            ConfigResource resource,
            IssueCollector issues) {
        String name = toResourceKey(entry).canonicalName();
        if (resource == null) {
            issues.add("CONFIG", "CONFIG_SQL_RESOURCE_MISSING", name,
                    "Config State decision has no SQL config_resource row");
            return;
        }
        if (revision(resource) != entry.decisionRevision()) {
            issues.add("CONFIG", "CONFIG_SQL_REVISION_MISMATCH", name,
                    "state=" + entry.decisionRevision()
                            + ", sql=" + revision(resource));
        }
        String expectedLifecycle = entry.state() == ConfigDecisionState.TOMBSTONE
                ? "TOMBSTONE" : "ACTIVE";
        if (!expectedLifecycle.equals(resource.getLifecycleStatus())) {
            issues.add("CONFIG", "CONFIG_SQL_LIFECYCLE_MISMATCH", name,
                    "state=" + expectedLifecycle
                            + ", sql=" + resource.getLifecycleStatus());
        }

        ConfigRelease current = configReleases.findByDecisionRevision(
                resource.getId(), entry.decisionRevision());
        if (current == null) {
            issues.add("CONFIG", "CONFIG_SQL_RELEASE_MISSING", name,
                    "No config_release row for decisionRevision="
                            + entry.decisionRevision());
        } else if (entry.state() == ConfigDecisionState.TOMBSTONE) {
            if (!"TOMBSTONE".equals(current.getReleaseType())) {
                issues.add("CONFIG", "CONFIG_SQL_RELEASE_MISMATCH", name,
                        "Tombstone decision is projected as " + current.getReleaseType());
            }
        } else {
            ConfigContentDigest currentDigest = digest(
                    entry, value(current.getContentRevision()));
            if (currentDigest == null || !releaseMatches(current, currentDigest)) {
                issues.add("CONFIG", "CONFIG_SQL_RELEASE_MISMATCH", name,
                        "Current release does not match an authoritative referenced content");
            }
        }

        for (ConfigContentDigest digest : entry.referencedContents()) {
            List<ConfigRelease> releases = configReleases.findByContentRevision(
                    resource.getId(), digest.contentRevision());
            if (releases.isEmpty()) {
                issues.add("CONFIG", "CONFIG_SQL_CONTENT_MISSING", name,
                        "No SQL release contains contentRevision="
                                + digest.contentRevision());
            } else if (releases.stream().noneMatch(release -> releaseMatches(release, digest))) {
                issues.add("CONFIG", "CONFIG_SQL_CONTENT_MISMATCH", name,
                        "SQL checksum/type differs for contentRevision="
                                + digest.contentRevision());
            }
        }
        checkRollout(entry, resource, issues, name);
    }

    private void checkRollout(
            ConfigProjectionEntry entry,
            ConfigResource resource,
            IssueCollector issues,
            String name) {
        List<ConfigRolloutDigest> activeRules = entry.rules().stream()
                .filter(rule -> rule.status() == RolloutRuleStatus.ACTIVE)
                .toList();
        ConfigRollout rollout = configRollouts.findActive(resource.getId());
        if (activeRules.isEmpty()) {
            if (rollout != null) {
                issues.add("CONFIG", "CONFIG_SQL_ROLLOUT_EXTRA", name,
                        "SQL has active rollout " + rollout.getRolloutId()
                                + " but Config State has no active rule");
            }
            return;
        }
        if (rollout == null) {
            issues.add("CONFIG", "CONFIG_SQL_ROLLOUT_MISSING", name,
                    "Config State has active rollout rules but SQL has no active rollout");
            return;
        }
        if (activeRules.size() != 1) {
            issues.add("CONFIG", "CONFIG_SQL_ROLLOUT_MISMATCH", name,
                    "SQL projection supports one active rollout, State has "
                            + activeRules.size() + " active rules");
        }
        ConfigRolloutDigest matching = activeRules.stream()
                .filter(rule -> rule.ruleId().equals(rollout.getRolloutId()))
                .findFirst()
                .orElse(null);
        if (matching == null) {
            issues.add("CONFIG", "CONFIG_SQL_ROLLOUT_MISMATCH", name,
                    "SQL rolloutId=" + rollout.getRolloutId()
                            + " does not match active State rules");
            return;
        }
        ConfigRelease candidate = configReleases.findByReleaseId(
                rollout.getCandidateReleaseId());
        if (candidate == null
                || value(candidate.getContentRevision()) != matching.targetContentRevision()) {
            issues.add("CONFIG", "CONFIG_SQL_ROLLOUT_MISMATCH", name,
                    "SQL candidate release does not match rule targetContentRevision="
                            + matching.targetContentRevision());
        }
    }

    private void checkServiceLifecycle(
            ServiceLifecycle lifecycle,
            ServiceDefinition service,
            IssueCollector issues) {
        String name = toResourceKey(lifecycle).canonicalName();
        if (lifecycle.status() == ServiceLifecycleStatus.DELETED) {
            if (service != null) {
                issues.add("REGISTRY", "REGISTRY_SQL_SERVICE_UNEXPECTED", name,
                        "Deleted Registry lifecycle still has SQL service_definition row");
            }
            return;
        }
        if (service == null) {
            issues.add("REGISTRY", "REGISTRY_SQL_SERVICE_MISSING", name,
                    "Active Registry lifecycle has no SQL service_definition row");
            return;
        }
        if (!ServiceDefinitionService.LIFECYCLE_ACTIVE.equals(
                service.getLifecycleState())) {
            issues.add("REGISTRY", "REGISTRY_SQL_LIFECYCLE_MISMATCH", name,
                    "state=ACTIVE, sql=" + service.getLifecycleState());
        }
        if (value(service.getServiceGeneration()) != lifecycle.generation()) {
            issues.add("REGISTRY", "REGISTRY_SQL_GENERATION_MISMATCH", name,
                    "state=" + lifecycle.generation()
                            + ", sql=" + value(service.getServiceGeneration()));
        }
    }

    private ConfigProjectionSnapshot configSnapshot() {
        for (int attempt = 1; attempt <= MAX_SCAN_ATTEMPTS; attempt++) {
            List<ConfigProjectionEntry> entries = new ArrayList<>();
            cloud.xuantong.config.state.ConfigKey after = null;
            long eventRevision = -1L;
            long compactionRevision = -1L;
            boolean changed = false;
            while (true) {
                ConfigProjectionSnapshot page = configPage(after);
                if (eventRevision < 0) {
                    eventRevision = page.eventRevision();
                    compactionRevision = page.compactionRevision();
                } else if (eventRevision != page.eventRevision()
                        || compactionRevision != page.compactionRevision()) {
                    changed = true;
                    break;
                }
                entries.addAll(page.entries());
                if (!page.hasMore()) {
                    return new ConfigProjectionSnapshot(
                            eventRevision, compactionRevision, entries, false);
                }
                if (page.entries().isEmpty()) {
                    throw new IllegalStateException(
                            "Config projection pagination made no progress");
                }
                after = page.entries().getLast().configKey();
            }
            if (!changed) {
                break;
            }
        }
        throw new IllegalStateException(
                "Config State changed during consistency scan; retry after writes are quiesced");
    }

    private ConfigProjectionSnapshot configPage(
            cloud.xuantong.config.state.ConfigKey after) {
        QueryResult result = join(stateClient().query(
                ConfigStateCodec.projectionSnapshotQuery(
                        configProperties.stateGroupId(),
                        new ConfigProjectionSnapshotRequest(after, PAGE_SIZE),
                        ReadOptions.linearizable())));
        if (!ConfigStateCodec.RESULT_PROJECTION_SNAPSHOT.equals(result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Config projection snapshot result: "
                            + result.resultType());
        }
        try {
            return ConfigStateCodec.decodeProjectionSnapshot(result.payload());
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Config projection snapshot", e);
        }
    }

    private RegistryScan registrySnapshot() {
        for (int attempt = 1; attempt <= MAX_SCAN_ATTEMPTS; attempt++) {
            List<ServiceLifecycle> services = new ArrayList<>();
            cloud.xuantong.registry.state.ServiceKey after = null;
            long registryRevision = -1L;
            long serverTimeEpochMs = 0L;
            long appliedIndex = -1L;
            boolean changed = false;
            while (true) {
                RegistryPage page = registryPage(after);
                if (registryRevision < 0) {
                    registryRevision = page.snapshot().registryRevision();
                    appliedIndex = page.appliedIndex();
                } else if (registryRevision != page.snapshot().registryRevision()
                        || appliedIndex != page.appliedIndex()) {
                    changed = true;
                    break;
                }
                serverTimeEpochMs = Math.max(
                        serverTimeEpochMs, page.snapshot().serverTimeEpochMs());
                services.addAll(page.snapshot().services());
                if (!page.snapshot().hasMore()) {
                    return new RegistryScan(
                            new ServiceLifecycleSnapshot(
                                    registryRevision,
                                    serverTimeEpochMs,
                                    services,
                                    false),
                            appliedIndex);
                }
                if (page.snapshot().services().isEmpty()) {
                    throw new IllegalStateException(
                            "Registry lifecycle pagination made no progress");
                }
                after = page.snapshot().services().getLast().serviceKey();
            }
            if (!changed) {
                break;
            }
        }
        throw new IllegalStateException(
                "Registry State changed during consistency scan; retry after writes are quiesced");
    }

    private RegistryPage registryPage(
            cloud.xuantong.registry.state.ServiceKey after) {
        QueryResult result = join(stateClient().query(
                RegistryStateCodec.serviceLifecycleSnapshotQuery(
                        registryProperties.stateGroupId(),
                        new ServiceLifecycleSnapshotRequest(after, PAGE_SIZE),
                        ReadOptions.linearizable())));
        if (!RegistryStateCodec.RESULT_SERVICE_LIFECYCLE_SNAPSHOT.equals(
                result.resultType())) {
            throw new IllegalStateException(
                    "Unexpected Registry lifecycle snapshot result: "
                            + result.resultType());
        }
        try {
            return new RegistryPage(
                    RegistryStateCodec.decodeServiceLifecycleSnapshot(result.payload()),
                    result.appliedIndex());
        } catch (IOException e) {
            throw new IllegalStateException("Malformed Registry lifecycle snapshot", e);
        }
    }

    private void requireAvailable() {
        if (!configProperties.isEnabled() || !registryProperties.isEnabled()) {
            throw new IllegalStateException(
                    "Config and Registry State must both be enabled for consistency validation");
        }
        if (directStateClient == null && (runtime == null || !runtime.isRunning())) {
            throw new IllegalStateException("State Plane is not running");
        }
    }

    private StateClient stateClient() {
        return directStateClient != null ? directStateClient : runtime.stateClient();
    }

    private static ConfigResourceKey toResourceKey(ConfigProjectionEntry entry) {
        return ConfigResourceKey.of(
                entry.configKey().namespace(),
                entry.configKey().group(),
                entry.configKey().dataId());
    }

    private static ServiceKey toResourceKey(ServiceLifecycle lifecycle) {
        return ServiceKey.of(
                lifecycle.serviceKey().namespace(),
                lifecycle.serviceKey().group(),
                lifecycle.serviceKey().serviceName());
    }

    private static ConfigContentDigest digest(
            ConfigProjectionEntry entry, long contentRevision) {
        return entry.referencedContents().stream()
                .filter(value -> value.contentRevision() == contentRevision)
                .findFirst()
                .orElse(null);
    }

    private static boolean releaseMatches(
            ConfigRelease release, ConfigContentDigest digest) {
        return value(release.getContentRevision()) == digest.contentRevision()
                && Objects.equals(release.getChecksum(), digest.contentHash())
                && Objects.equals(release.getContentType(), digest.contentType());
    }

    private static long revision(ConfigResource resource) {
        return value(resource.getRevision());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("State consistency query failed", cause);
        }
    }

    public record ConsistencyReport(
            boolean consistent,
            boolean complete,
            long checkedAtEpochMs,
            ConfigSummary config,
            RegistrySummary registry,
            List<ConsistencyIssue> issues,
            boolean issuesTruncated) {

        public ConsistencyReport {
            issues = List.copyOf(issues == null ? List.of() : issues);
        }
    }

    public record ConfigSummary(
            long eventRevision,
            long compactionRevision,
            long stateDecisionCount,
            long sqlResourceCount) {
    }

    public record RegistrySummary(
            long registryRevision,
            long appliedIndex,
            long serverTimeEpochMs,
            long stateServiceCount,
            long sqlServiceCount) {
    }

    public record ConsistencyIssue(
            String domain,
            String code,
            String resource,
            String detail) {
    }

    private static final class IssueCollector {
        private final int limit;
        private final List<ConsistencyIssue> values = new ArrayList<>();
        private boolean truncated;

        private IssueCollector(int limit) {
            this.limit = limit;
        }

        private void add(String domain, String code, String resource, String detail) {
            if (values.size() >= limit) {
                truncated = true;
                return;
            }
            values.add(new ConsistencyIssue(domain, code, resource, detail));
        }

        private boolean isEmpty() {
            return values.isEmpty() && !truncated;
        }

        private boolean truncated() {
            return truncated;
        }

        private List<ConsistencyIssue> values() {
            return List.copyOf(values);
        }
    }

    private record RegistryPage(
            ServiceLifecycleSnapshot snapshot,
            long appliedIndex) {
    }

    private record RegistryScan(
            ServiceLifecycleSnapshot snapshot,
            long appliedIndex) {
    }
}
