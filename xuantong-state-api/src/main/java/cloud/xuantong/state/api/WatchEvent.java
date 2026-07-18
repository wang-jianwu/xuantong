package cloud.xuantong.state.api;

public record WatchEvent(
        StateRevision revision,
        String eventType,
        int schemaVersion,
        byte[] payload) {

    public WatchEvent {
        if (revision == null || !revision.type().isWatchCursor()) {
            throw new IllegalArgumentException(
                    "revision must be a CONFIG_EVENT or REGISTRY cursor");
        }
        if (revision.value() < 1) {
            throw new IllegalArgumentException("event revision must be positive");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        eventType = eventType.trim();
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
