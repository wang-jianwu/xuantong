package cloud.xuantong.raft.ratis;

import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;

public record RatisPeerDefinition(String nodeId, String host, int port) {
    public RatisPeerDefinition {
        nodeId = requireText("nodeId", nodeId);
        host = requireText("host", host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public RaftPeer toRaftPeer() {
        return toRaftPeer(RaftPeerRole.FOLLOWER);
    }

    public RaftPeer toRaftPeer(RaftPeerRole startupRole) {
        return RaftPeer.newBuilder()
                .setId(nodeId)
                .setAddress(address())
                .setStartupRole(startupRole)
                .build();
    }

    public String address() {
        return host.indexOf(':') >= 0 ? '[' + host + "]:" + port : host + ':' + port;
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
