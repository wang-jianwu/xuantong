package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RatisGroupDefinition(StateGroupId groupId, List<RatisPeerDefinition> peers) {
    public RatisGroupDefinition {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        peers = List.copyOf(peers == null ? List.of() : peers);
        if (peers.isEmpty()) {
            throw new IllegalArgumentException("peers must not be empty");
        }
        Set<String> nodeIds = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (RatisPeerDefinition peer : peers) {
            if (!nodeIds.add(peer.nodeId())) {
                throw new IllegalArgumentException("Duplicate Raft nodeId: " + peer.nodeId());
            }
            String address = peer.host() + ":" + peer.port();
            if (!addresses.add(address)) {
                throw new IllegalArgumentException("Duplicate Raft address: " + address);
            }
        }
    }

    public RaftGroupId toRaftGroupId() {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("xuantong-raft-group:" + groupId.canonicalName())
                        .getBytes(StandardCharsets.UTF_8));
        return RaftGroupId.valueOf(uuid);
    }

    public RaftGroup toRaftGroup() {
        return RaftGroup.valueOf(toRaftGroupId(),
                peers.stream().map(RatisPeerDefinition::toRaftPeer).toList());
    }

    public RatisPeerDefinition requirePeer(String nodeId) {
        return peers.stream()
                .filter(peer -> peer.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Raft group " + groupId + " does not contain node " + nodeId));
    }
}
