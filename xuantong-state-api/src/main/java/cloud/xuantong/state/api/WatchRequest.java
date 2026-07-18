package cloud.xuantong.state.api;

public record WatchRequest(
        StateRevision afterRevision,
        String watchType,
        int schemaVersion,
        byte[] selector,
        int maxBatchSize,
        ReadOptions readOptions) {

    public WatchRequest {
        if (afterRevision == null) {
            throw new IllegalArgumentException("afterRevision must not be null");
        }
        if (!afterRevision.type().isWatchCursor()) {
            throw new IllegalArgumentException(
                    "Watch requires CONFIG_EVENT or REGISTRY revision");
        }
        if (watchType == null || watchType.isBlank()) {
            throw new IllegalArgumentException("watchType must not be blank");
        }
        watchType = watchType.trim();
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        selector = selector == null ? new byte[0] : selector.clone();
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (readOptions == null) {
            throw new IllegalArgumentException("readOptions must not be null");
        }
        StateRevision minimumRevision = readOptions.minimumRevision();
        if (minimumRevision != null
                && !afterRevision.sameCoordinate(minimumRevision)) {
            throw new IllegalArgumentException(
                    "minimumRevision must use the same Watch coordinate");
        }
    }

    public StateGroupId groupId() {
        return afterRevision.groupId();
    }

    @Override
    public byte[] selector() {
        return selector.clone();
    }
}
