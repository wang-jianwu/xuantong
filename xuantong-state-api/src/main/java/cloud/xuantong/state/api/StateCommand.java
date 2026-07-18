package cloud.xuantong.state.api;

public record StateCommand(
        StateGroupId groupId,
        String operationId,
        String commandType,
        int schemaVersion,
        byte[] payload) {

    public StateCommand {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        operationId = required("operationId", operationId);
        commandType = required("commandType", commandType);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
