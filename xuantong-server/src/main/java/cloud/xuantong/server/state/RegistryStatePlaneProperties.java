package cloud.xuantong.server.state;

import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
import cloud.xuantong.registry.state.RegistryStateOptions;
import cloud.xuantong.state.api.StateGroupId;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.time.Duration;
import java.util.List;

/** Registry Group configuration hosted by the same compact State Node. */
@Configuration
public class RegistryStatePlaneProperties {
    @Inject("${statePlane.registry.enabled:false}")
    private boolean enabled;
    @Inject("${statePlane.registry.groupId:registry-default}")
    private String groupId;
    @Inject("${statePlane.registry.minLeaseTtlMs:3000}")
    private long minLeaseTtlMs;
    @Inject("${statePlane.registry.maxLeaseTtlMs:120000}")
    private long maxLeaseTtlMs;
    @Inject("${statePlane.registry.maxInstances:1000000}")
    private int maxInstances;
    @Inject("${statePlane.registry.maxServices:1000000}")
    private int maxServices;
    @Inject("${statePlane.registry.maxRenewBatchSize:1000}")
    private int maxRenewBatchSize;
    @Inject("${statePlane.registry.changeLogCapacity:100000}")
    private int changeLogCapacity;
    @Inject("${statePlane.registry.maxOperationRecords:200000}")
    private int maxOperationRecords;
    @Inject("${statePlane.registry.expirationIntervalMs:1000}")
    private long expirationIntervalMs;
    @Inject("${statePlane.registry.expirationBatchSize:1000}")
    private int expirationBatchSize;

    public RegistryStatePlaneProperties() {
    }

    public RegistryStatePlaneProperties(
            boolean enabled,
            String groupId,
            long minLeaseTtlMs,
            long maxLeaseTtlMs) {
        this.enabled = enabled;
        this.groupId = groupId;
        this.minLeaseTtlMs = minLeaseTtlMs;
        this.maxLeaseTtlMs = maxLeaseTtlMs;
        this.maxInstances = 100_000;
        this.maxServices = 100_000;
        this.maxRenewBatchSize = 1_000;
        this.changeLogCapacity = 10_000;
        this.maxOperationRecords = 100_000;
        this.expirationIntervalMs = 250;
        this.expirationBatchSize = 1_000;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public StateGroupId stateGroupId() {
        return StateGroupId.registry(required("statePlane.registry.groupId", groupId));
    }

    public RatisGroupDefinition groupDefinition(List<RatisPeerDefinition> peers) {
        if (peers == null || peers.isEmpty()) {
            throw new IllegalStateException("Registry State peers must not be empty");
        }
        return new RatisGroupDefinition(stateGroupId(), peers);
    }

    public RegistryStateOptions stateOptions() {
        return new RegistryStateOptions(
                minLeaseTtlMs,
                maxLeaseTtlMs,
                maxServices,
                maxInstances,
                maxRenewBatchSize,
                changeLogCapacity,
                maxOperationRecords);
    }

    public Duration expirationInterval() {
        if (expirationIntervalMs < 100) {
            throw new IllegalStateException(
                    "statePlane.registry.expirationIntervalMs must be at least 100");
        }
        return Duration.ofMillis(expirationIntervalMs);
    }

    public int expirationBatchSize() {
        if (expirationBatchSize < 1 || expirationBatchSize > maxInstances) {
            throw new IllegalStateException(
                    "statePlane.registry.expirationBatchSize is invalid");
        }
        return expirationBatchSize;
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }
}
