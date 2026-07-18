package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ConfigStateOptions;
import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisNodeOptions;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
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
    @Inject("${statePlane.config.storageDirectory:./data/state}")
    private String storageDirectory;
    @Inject("${statePlane.config.allowSingleNode:false}")
    private boolean allowSingleNode;
    @Inject("${statePlane.config.electionTimeoutMinMs:1000}")
    private long electionTimeoutMinMs;
    @Inject("${statePlane.config.electionTimeoutMaxMs:2000}")
    private long electionTimeoutMaxMs;
    @Inject("${statePlane.config.requestTimeoutMs:5000}")
    private long requestTimeoutMs;
    @Inject("${statePlane.config.clientMaxAttempts:5}")
    private int clientMaxAttempts;
    @Inject("${statePlane.config.snapshotAutoTriggerThreshold:10000}")
    private long snapshotAutoTriggerThreshold;
    @Inject("${statePlane.config.snapshotOnShutdown:true}")
    private boolean snapshotOnShutdown;
    @Inject("${statePlane.config.maxInlineContentBytes:1048576}")
    private int maxInlineContentBytes;
    @Inject("${statePlane.config.changeLogCapacity:10000}")
    private int changeLogCapacity;
    @Inject("${statePlane.config.maxOperationRecords:100000}")
    private int maxOperationRecords;
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
        this.storageDirectory = storageDirectory.toString();
        this.allowSingleNode = allowSingleNode;
        this.electionTimeoutMinMs = 200;
        this.electionTimeoutMaxMs = 400;
        this.requestTimeoutMs = 2_000;
        this.clientMaxAttempts = 5;
        this.snapshotAutoTriggerThreshold = 10_000;
        this.snapshotOnShutdown = false;
        this.maxInlineContentBytes = 1024 * 1024;
        this.changeLogCapacity = 10_000;
        this.maxOperationRecords = 100_000;
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
                positiveDuration("electionTimeoutMinMs", electionTimeoutMinMs),
                positiveDuration("electionTimeoutMaxMs", electionTimeoutMaxMs),
                positiveDuration("requestTimeoutMs", requestTimeoutMs),
                snapshotAutoTriggerThreshold,
                snapshotOnShutdown);
    }

    public Duration requestTimeout() {
        return positiveDuration("requestTimeoutMs", requestTimeoutMs);
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
                maxRulesPerDecision);
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
