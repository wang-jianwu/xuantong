package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ConfigStateOptions;
import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisNodeOptions;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
import cloud.xuantong.raft.ratis.RatisStartupMode;
import cloud.xuantong.raft.ratis.RatisVersionRequirement;
import cloud.xuantong.raft.ratis.RatisStateMessageVersions;
import cloud.xuantong.state.api.StateGroupId;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Explicit compact-deployment configuration for the authoritative Config Group. */
@Configuration
public class ConfigStatePlaneProperties {
    @Inject("${statePlane.config.enabled:false}")
    private boolean enabled;
    @Inject("${statePlane.config.localNodeId:}")
    private String localNodeId;
    @Inject("${statePlane.config.groupId:config-default}")
    private String groupId;
    @Inject("${statePlane.config.peers:}")
    private String peers;
    @Inject("${statePlane.config.rpcBindHost:}")
    private String rpcBindHost;
    @Inject("${statePlane.config.rpcBindPort:0}")
    private int rpcBindPort;
    @Inject("${statePlane.config.storageDirectory:./data/state}")
    private String storageDirectory;
    @Inject("${statePlane.config.storageFreeSpaceMinBytes:536870912}")
    private long storageFreeSpaceMinBytes;
    @Inject("${statePlane.config.allowSingleNode:false}")
    private boolean allowSingleNode;
    @Inject("${statePlane.config.joinExisting:false}")
    private boolean joinExisting;
    @Inject("${statePlane.config.electionTimeoutMinMs:1000}")
    private long electionTimeoutMinMs;
    @Inject("${statePlane.config.electionTimeoutMaxMs:2000}")
    private long electionTimeoutMaxMs;
    @Inject("${statePlane.config.requestTimeoutMs:5000}")
    private long requestTimeoutMs;
    @Inject("${statePlane.config.startupReadyTimeoutMs:15000}")
    private long startupReadyTimeoutMs;
    @Inject("${statePlane.config.clientMaxAttempts:5}")
    private int clientMaxAttempts;
    @Inject("${statePlane.config.snapshotAutoTriggerThreshold:10000}")
    private long snapshotAutoTriggerThreshold;
    @Inject("${statePlane.config.snapshotOnShutdown:true}")
    private boolean snapshotOnShutdown;
    @Inject("${statePlane.config.snapshotRetentionFileCount:3}")
    private int snapshotRetentionFileCount;
    @Inject("${statePlane.config.snapshotManagementTimeoutMs:120000}")
    private long snapshotManagementTimeoutMs;
    @Inject("${statePlane.config.membershipCatchUpTimeoutMs:120000}")
    private long membershipCatchUpTimeoutMs;
    @Inject("${statePlane.config.membershipMaximumCatchUpGap:0}")
    private long membershipMaximumCatchUpGap;
    @Inject("${statePlane.config.maxInlineContentBytes:1048576}")
    private int maxInlineContentBytes;
    @Inject("${statePlane.config.changeLogCapacity:10000}")
    private int changeLogCapacity;
    @Inject("${statePlane.config.maxOperationRecords:100000}")
    private int maxOperationRecords;
    @Inject("${statePlane.config.operationReplayWindow:75000}")
    private int operationReplayWindow;
    @Inject("${statePlane.config.maxRulesPerDecision:128}")
    private int maxRulesPerDecision;

    public ConfigStatePlaneProperties() {
    }

    public ConfigStatePlaneProperties(
            boolean enabled,
            String localNodeId,
            String groupId,
            String peers,
            Path storageDirectory,
            boolean allowSingleNode) {
        this.enabled = enabled;
        this.localNodeId = localNodeId;
        this.groupId = groupId;
        this.peers = peers;
        this.rpcBindHost = "";
        this.rpcBindPort = 0;
        this.storageDirectory = storageDirectory.toString();
        this.storageFreeSpaceMinBytes = 0L;
        this.allowSingleNode = allowSingleNode;
        this.joinExisting = false;
        this.electionTimeoutMinMs = 200;
        this.electionTimeoutMaxMs = 400;
        this.requestTimeoutMs = 2_000;
        this.startupReadyTimeoutMs = 5_000;
        this.clientMaxAttempts = 5;
        this.snapshotAutoTriggerThreshold = 10_000;
        this.snapshotOnShutdown = false;
        this.snapshotRetentionFileCount =
                RatisNodeOptions.DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT;
        this.snapshotManagementTimeoutMs = 30_000;
        this.membershipCatchUpTimeoutMs = 30_000;
        this.membershipMaximumCatchUpGap = 0;
        this.maxInlineContentBytes = 1024 * 1024;
        this.changeLogCapacity = 10_000;
        this.maxOperationRecords = 100_000;
        this.operationReplayWindow = 75_000;
        this.maxRulesPerDecision = 128;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String localNodeId() {
        return required("statePlane.config.localNodeId", localNodeId);
    }

    public StateGroupId stateGroupId() {
        return StateGroupId.config(required("statePlane.config.groupId", groupId));
    }

    public RatisGroupDefinition groupDefinition() {
        List<RatisPeerDefinition> definitions = parsePeers(peers);
        validateTopology(definitions.size());
        RatisGroupDefinition group = new RatisGroupDefinition(stateGroupId(), definitions);
        group.requirePeer(localNodeId());
        return group;
    }

    public RatisNodeOptions nodeOptions(RatisGroupDefinition group) {
        return new RatisNodeOptions(
                localNodeId(),
                group,
                Path.of(required(
                        "statePlane.config.storageDirectory", storageDirectory))
                        .toAbsolutePath()
                        .normalize(),
                storageFreeSpaceMinBytes(),
                positiveDuration("electionTimeoutMinMs", electionTimeoutMinMs),
                positiveDuration("electionTimeoutMaxMs", electionTimeoutMaxMs),
                positiveDuration("requestTimeoutMs", requestTimeoutMs),
                snapshotAutoTriggerThreshold,
                snapshotOnShutdown,
                snapshotRetentionFileCount(),
                joinExisting
                        ? RatisStartupMode.JOIN_EXISTING
                        : RatisStartupMode.BOOTSTRAP_OR_RECOVER,
                rpcBindHost(group),
                rpcBindPort(group));
    }

    public Duration requestTimeout() {
        return positiveDuration("requestTimeoutMs", requestTimeoutMs);
    }

    public Duration startupReadyTimeout() {
        return positiveDuration("startupReadyTimeoutMs", startupReadyTimeoutMs);
    }

    public int clientMaxAttempts() {
        if (clientMaxAttempts < 1) {
            throw new IllegalStateException(
                    "statePlane.config.clientMaxAttempts must be positive");
        }
        return clientMaxAttempts;
    }

    public ConfigStateOptions stateOptions() {
        return new ConfigStateOptions(
                maxInlineContentBytes,
                changeLogCapacity,
                maxOperationRecords,
                operationReplayWindow,
                maxRulesPerDecision);
    }

    public boolean joinExisting() {
        return joinExisting;
    }

    public Path storageDirectory() {
        return Path.of(required(
                        "statePlane.config.storageDirectory", storageDirectory))
                .toAbsolutePath()
                .normalize();
    }

    public long storageFreeSpaceMinBytes() {
        if (storageFreeSpaceMinBytes < 0L) {
            throw new IllegalStateException(
                    "statePlane.config.storageFreeSpaceMinBytes must not be negative");
        }
        return storageFreeSpaceMinBytes;
    }

    public int snapshotRetentionFileCount() {
        if (snapshotRetentionFileCount < 1 || snapshotRetentionFileCount > 64) {
            throw new IllegalStateException(
                    "statePlane.config.snapshotRetentionFileCount must be between 1 and 64");
        }
        return snapshotRetentionFileCount;
    }

    public Duration snapshotManagementTimeout() {
        return positiveDuration(
                "snapshotManagementTimeoutMs", snapshotManagementTimeoutMs);
    }

    public boolean allowSingleNode() {
        return allowSingleNode;
    }

    private String rpcBindHost(RatisGroupDefinition group) {
        if (rpcBindHost == null || rpcBindHost.isBlank()) {
            return group.requirePeer(localNodeId()).host();
        }
        return rpcBindHost.trim();
    }

    private int rpcBindPort(RatisGroupDefinition group) {
        if (rpcBindPort == 0) {
            return group.requirePeer(localNodeId()).port();
        }
        if (rpcBindPort < 1 || rpcBindPort > 65_535) {
            throw new IllegalStateException(
                    "statePlane.config.rpcBindPort must be between 1 and 65535");
        }
        return rpcBindPort;
    }

    public Duration membershipCatchUpTimeout() {
        return positiveDuration(
                "membershipCatchUpTimeoutMs", membershipCatchUpTimeoutMs);
    }

    public long membershipMaximumCatchUpGap() {
        if (membershipMaximumCatchUpGap < 0) {
            throw new IllegalStateException(
                    "statePlane.config.membershipMaximumCatchUpGap must not be negative");
        }
        return membershipMaximumCatchUpGap;
    }

    public RatisVersionRequirement versionRequirement() {
        return new RatisVersionRequirement(
                stateGroupId(),
                RatisStateMessageVersions.CURRENT_ENVELOPE_VERSION,
                cloud.xuantong.config.state.ConfigStateCodec.SCHEMA_VERSION,
                cloud.xuantong.config.state.ConfigStateMachine.SNAPSHOT_SCHEMA_VERSION);
    }

    static List<RatisPeerDefinition> parsePeers(String configuredPeers) {
        if (configuredPeers == null || configuredPeers.isBlank()) {
            throw new IllegalStateException("statePlane.config.peers must not be blank");
        }
        List<RatisPeerDefinition> definitions = new ArrayList<>();
        for (String item : configuredPeers.split(",")) {
            String peer = item.trim();
            int at = peer.indexOf('@');
            if (at < 1 || at == peer.length() - 1 || peer.indexOf('@', at + 1) >= 0) {
                throw new IllegalStateException(
                        "Invalid State peer; expected nodeId@host:port: " + peer);
            }
            String nodeId = peer.substring(0, at).trim();
            HostPort hostPort = parseHostPort(peer.substring(at + 1).trim());
            definitions.add(new RatisPeerDefinition(
                    nodeId, hostPort.host(), hostPort.port()));
        }
        return List.copyOf(definitions);
    }

    private void validateTopology(int peerCount) {
        if (peerCount == 1 && allowSingleNode) {
            return;
        }
        if (peerCount != 3 && peerCount != 5) {
            throw new IllegalStateException(
                    "Config State requires 3 or 5 peers; single-node mode must be explicitly enabled for local development");
        }
    }

    private static HostPort parseHostPort(String address) {
        String host;
        String portText;
        if (address.startsWith("[")) {
            int bracket = address.indexOf(']');
            if (bracket < 2 || bracket + 2 > address.length()
                    || address.charAt(bracket + 1) != ':') {
                throw new IllegalStateException("Invalid bracketed State peer address: " + address);
            }
            host = address.substring(1, bracket);
            portText = address.substring(bracket + 2);
        } else {
            int colon = address.lastIndexOf(':');
            if (colon < 1 || colon == address.length() - 1
                    || address.indexOf(':') != colon) {
                throw new IllegalStateException(
                        "Invalid State peer address; IPv6 must use brackets: " + address);
            }
            host = address.substring(0, colon);
            portText = address.substring(colon + 1);
        }
        try {
            return new HostPort(host, Integer.parseInt(portText));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid State peer port: " + address, e);
        }
    }

    private static Duration positiveDuration(String field, long milliseconds) {
        if (milliseconds < 1) {
            throw new IllegalStateException(
                    "statePlane.config." + field + " must be positive");
        }
        return Duration.ofMillis(milliseconds);
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }

    private record HostPort(String host, int port) {
    }
}
