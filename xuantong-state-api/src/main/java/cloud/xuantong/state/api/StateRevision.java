package cloud.xuantong.state.api;

public record StateRevision(
        StateGroupId groupId,
        StateRevisionType type,
        String scope,
        long value) implements Comparable<StateRevision> {

    public StateRevision {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (groupId.type() != type.groupType()) {
            throw new IllegalArgumentException("Revision type " + type
                    + " is not valid for group " + groupId);
        }
        scope = scope == null ? "" : scope.trim();
        if (type == StateRevisionType.CONFIG_DECISION && scope.isEmpty()) {
            throw new IllegalArgumentException(
                    "CONFIG_DECISION revision requires a config-key scope");
        }
        if (type != StateRevisionType.CONFIG_DECISION && !scope.isEmpty()) {
            throw new IllegalArgumentException(type + " revision must not have a scope");
        }
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }

    public static StateRevision configDecision(
            StateGroupId groupId, String configKey, long value) {
        return new StateRevision(
                groupId, StateRevisionType.CONFIG_DECISION, configKey, value);
    }

    public static StateRevision configEvent(StateGroupId groupId, long value) {
        return new StateRevision(groupId, StateRevisionType.CONFIG_EVENT, "", value);
    }

    public static StateRevision registry(StateGroupId groupId, long value) {
        return new StateRevision(groupId, StateRevisionType.REGISTRY, "", value);
    }

    public boolean sameCoordinate(StateRevision other) {
        return other != null
                && groupId.equals(other.groupId)
                && type == other.type
                && scope.equals(other.scope);
    }

    public StateRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow for " + this);
        }
        return new StateRevision(groupId, type, scope, value + 1);
    }

    @Override
    public int compareTo(StateRevision other) {
        if (!sameCoordinate(other)) {
            throw new IllegalArgumentException(
                    "Revisions from different coordinates are not comparable: "
                            + this + " vs " + other);
        }
        return Long.compare(value, other.value);
    }
}
