package cloud.xuantong.state.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ApplyResult(
        StateGroupId groupId,
        String operationId,
        ApplyStatus status,
        long appliedIndex,
        String resultType,
        byte[] payload,
        List<StateRevision> revisions) {

    public ApplyResult {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        operationId = operationId.trim();
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (appliedIndex < 1) {
            throw new IllegalArgumentException("appliedIndex must be positive");
        }
        if (resultType == null || resultType.isBlank()) {
            throw new IllegalArgumentException("resultType must not be blank");
        }
        resultType = resultType.trim();
        payload = payload == null ? new byte[0] : payload.clone();
        revisions = List.copyOf(revisions == null ? List.of() : revisions);
        validateRevisions(groupId, revisions);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static void validateRevisions(
            StateGroupId groupId, List<StateRevision> revisions) {
        Set<String> coordinates = new HashSet<>();
        for (StateRevision revision : revisions) {
            if (!groupId.equals(revision.groupId())) {
                throw new IllegalArgumentException(
                        "Result revision belongs to another group: " + revision);
            }
            String coordinate = revision.type() + "\u0000" + revision.scope();
            if (!coordinates.add(coordinate)) {
                throw new IllegalArgumentException(
                        "Duplicate result revision coordinate: " + revision);
            }
        }
    }
}
