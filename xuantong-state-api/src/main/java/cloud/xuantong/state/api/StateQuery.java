package cloud.xuantong.state.api;

public record StateQuery(
        StateGroupId groupId,
        String queryType,
        int schemaVersion,
        byte[] payload,
        ReadOptions readOptions) {

    public StateQuery {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (queryType == null || queryType.isBlank()) {
            throw new IllegalArgumentException("queryType must not be blank");
        }
        queryType = queryType.trim();
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        payload = payload == null ? new byte[0] : payload.clone();
        if (readOptions == null) {
            throw new IllegalArgumentException("readOptions must not be null");
        }
        StateRevision minimumRevision = readOptions.minimumRevision();
        if (minimumRevision != null && !groupId.equals(minimumRevision.groupId())) {
            throw new IllegalArgumentException(
                    "minimumRevision must belong to query group " + groupId);
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
