package cloud.xuantong.raft.ratis;

import java.util.List;

/** Final verified result of changing every compact State Group to one topology. */
public record RatisMembershipChangeResult(
        List<RatisPeerDefinition> previousVoters,
        List<RatisPeerDefinition> targetVoters,
        List<String> addedNodeIds,
        List<String> removedNodeIds,
        List<RatisGroupMembershipView> groups) {

    public RatisMembershipChangeResult {
        previousVoters = List.copyOf(previousVoters);
        targetVoters = List.copyOf(targetVoters);
        addedNodeIds = List.copyOf(addedNodeIds);
        removedNodeIds = List.copyOf(removedNodeIds);
        groups = List.copyOf(groups);
    }
}
