package cloud.xuantong.registry.state;

import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateRevision;

import java.util.List;

public record ResolvedRegistryOperation(
        boolean found,
        String requestHash,
        ApplyStatus status,
        String resultType,
        byte[] payload,
        List<StateRevision> revisions) {

    public ResolvedRegistryOperation {
        requestHash = requestHash == null ? "" : requestHash;
        resultType = resultType == null ? "" : resultType;
        payload = payload == null ? new byte[0] : payload.clone();
        revisions = List.copyOf(revisions == null ? List.of() : revisions);
        if (found && (requestHash.isBlank() || status == null || resultType.isBlank())) {
            throw new IllegalArgumentException("Resolved operation is incomplete");
        }
        if (!found && (!requestHash.isEmpty() || status != null
                || !resultType.isEmpty() || payload.length > 0 || !revisions.isEmpty())) {
            throw new IllegalArgumentException("Missing operation must not contain state");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static ResolvedRegistryOperation missing() {
        return new ResolvedRegistryOperation(false, "", null, "", new byte[0], List.of());
    }
}
