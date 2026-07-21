package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;

/** Read-only local runtime status for one Raft Group hosted by a State Node. */
public record RatisGroupRuntimeStatus(
        String nodeId,
        StateGroupId groupId,
        boolean alive,
        boolean leader,
        boolean leaderReady,
        String leaderId,
        long currentTerm,
        long lastCommittedIndex,
        long lastAppliedIndex) {

    public RatisGroupRuntimeStatus {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        leaderId = leaderId == null ? "" : leaderId;
    }
}
