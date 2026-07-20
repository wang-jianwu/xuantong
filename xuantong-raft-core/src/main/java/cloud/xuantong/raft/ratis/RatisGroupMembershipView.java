package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;

import java.util.List;

/** Observed committed membership and log watermark of one Raft Group. */
public record RatisGroupMembershipView(
        StateGroupId groupId,
        List<RatisPeerDefinition> voters,
        List<RatisPeerDefinition> listeners,
        String leaderId,
        long committedIndex,
        boolean jointConsensus) {

    public RatisGroupMembershipView {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        voters = List.copyOf(voters == null ? List.of() : voters);
        listeners = List.copyOf(listeners == null ? List.of() : listeners);
        leaderId = leaderId == null ? "" : leaderId.trim();
        if (committedIndex < -1) {
            throw new IllegalArgumentException("committedIndex must not be below -1");
        }
    }
}
