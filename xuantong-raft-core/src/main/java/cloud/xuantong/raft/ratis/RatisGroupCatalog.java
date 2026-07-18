package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.protocol.RaftGroupId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable catalog for the State Groups hosted by one compact State cluster. */
public final class RatisGroupCatalog {
    private final RatisGroupDefinition bootstrapGroup;
    private final Map<StateGroupId, RatisGroupDefinition> byStateGroup;
    private final Map<RaftGroupId, StateGroupId> byRaftGroup;

    public RatisGroupCatalog(
            RatisGroupDefinition bootstrapGroup,
            Collection<RatisGroupDefinition> groups) {
        if (bootstrapGroup == null) {
            throw new IllegalArgumentException("bootstrapGroup must not be null");
        }
        List<RatisGroupDefinition> definitions = List.copyOf(
                groups == null ? List.of() : groups);
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("groups must not be empty");
        }

        Map<StateGroupId, RatisGroupDefinition> stateGroups = new LinkedHashMap<>();
        Map<RaftGroupId, StateGroupId> raftGroups = new LinkedHashMap<>();
        for (RatisGroupDefinition definition : definitions) {
            if (stateGroups.putIfAbsent(definition.groupId(), definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate State Group: " + definition.groupId());
            }
            StateGroupId previous = raftGroups.putIfAbsent(
                    definition.toRaftGroupId(), definition.groupId());
            if (previous != null) {
                throw new IllegalArgumentException(
                        "State Groups map to the same Raft Group: "
                                + previous + " and " + definition.groupId());
            }
            requireCompactTopology(bootstrapGroup, definition);
        }
        if (!stateGroups.containsKey(bootstrapGroup.groupId())) {
            throw new IllegalArgumentException(
                    "groups must include bootstrapGroup " + bootstrapGroup.groupId());
        }
        this.bootstrapGroup = bootstrapGroup;
        this.byStateGroup = Map.copyOf(stateGroups);
        this.byRaftGroup = Map.copyOf(raftGroups);
    }

    public static RatisGroupCatalog compact(
            RatisGroupDefinition configGroup,
            RatisGroupDefinition registryGroup) {
        if (configGroup == null
                || configGroup.groupId().type()
                != cloud.xuantong.state.api.StateGroupType.CONFIG) {
            throw new IllegalArgumentException(
                    "configGroup must have CONFIG group type");
        }
        if (registryGroup == null
                || registryGroup.groupId().type()
                != cloud.xuantong.state.api.StateGroupType.REGISTRY) {
            throw new IllegalArgumentException(
                    "registryGroup must have REGISTRY group type");
        }
        return new RatisGroupCatalog(
                configGroup, List.of(configGroup, registryGroup));
    }

    public RatisGroupDefinition bootstrapGroup() {
        return bootstrapGroup;
    }

    public List<RatisGroupDefinition> groups() {
        return List.copyOf(byStateGroup.values());
    }

    public Set<StateGroupId> stateGroupIds() {
        return byStateGroup.keySet();
    }

    public RatisGroupDefinition requireGroup(StateGroupId groupId) {
        RatisGroupDefinition group = byStateGroup.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Unknown State Group: " + groupId);
        }
        return group;
    }

    public StateGroupId requireStateGroup(RaftGroupId groupId) {
        StateGroupId stateGroupId = byRaftGroup.get(groupId);
        if (stateGroupId == null) {
            throw new IllegalArgumentException("Unknown Raft Group: " + groupId);
        }
        return stateGroupId;
    }

    Map<RaftGroupId, StateGroupId> raftGroupMappings() {
        return byRaftGroup;
    }

    private static void requireCompactTopology(
            RatisGroupDefinition bootstrap,
            RatisGroupDefinition candidate) {
        Map<String, RatisPeerDefinition> expected = bootstrap.peers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        RatisPeerDefinition::nodeId, Function.identity()));
        Map<String, RatisPeerDefinition> actual = candidate.peers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        RatisPeerDefinition::nodeId, Function.identity()));
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Compact State Groups must use the same peer topology: "
                            + candidate.groupId());
        }
    }
}
