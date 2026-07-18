package cloud.xuantong.state.api;

public enum StateRevisionType {
    CONFIG_DECISION(StateGroupType.CONFIG, false),
    CONFIG_EVENT(StateGroupType.CONFIG, true),
    REGISTRY(StateGroupType.REGISTRY, true);

    private final StateGroupType groupType;
    private final boolean watchCursor;

    StateRevisionType(StateGroupType groupType, boolean watchCursor) {
        this.groupType = groupType;
        this.watchCursor = watchCursor;
    }

    public StateGroupType groupType() {
        return groupType;
    }

    public boolean isWatchCursor() {
        return watchCursor;
    }
}
