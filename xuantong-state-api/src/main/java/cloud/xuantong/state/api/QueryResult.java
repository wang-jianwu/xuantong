package cloud.xuantong.state.api;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record QueryResult(
        StateGroupId groupId,
        long appliedIndex,
        boolean stale,
        String resultType,
        byte[] payload,
        List<StateRevision> revisions) {

    public QueryResult {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (appliedIndex < 0) {
            throw new IllegalArgumentException("appliedIndex must not be negative");
        }
        if (resultType == null || resultType.isBlank()) {
            throw new IllegalArgumentException("resultType must not be blank");
        }
        resultType = resultType.trim();
        payload = payload == null ? new byte[0] : payload.clone();
        revisions = List.copyOf(revisions == null ? List.of() : revisions);
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

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
