package cloud.xuantong.config.state;

import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateRevision;

import java.util.List;

/** Result of resolving a possibly ambiguous committed operation. */
public record ResolvedConfigOperation(
        boolean found,
        String requestHash,
        ApplyStatus status,
        String resultType,
        byte[] payload,
        List<StateRevision> revisions) {

    public ResolvedConfigOperation {
        requestHash = requestHash == null ? "" : requestHash.trim();
        resultType = resultType == null ? "" : resultType.trim();
        payload = payload == null ? new byte[0] : payload.clone();
        revisions = List.copyOf(revisions == null ? List.of() : revisions);
        if (found) {
            ConfigContentDraft.validateSha256(requestHash);
            if (status == null || resultType.isEmpty()) {
                throw new IllegalArgumentException(
                        "found operation requires status and resultType");
            }
        } else if (!requestHash.isEmpty() || status != null || !resultType.isEmpty()
                || payload.length != 0 || !revisions.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing operation must not carry operation data");
        }
    }

    public static ResolvedConfigOperation missing() {
        return new ResolvedConfigOperation(
                false, "", null, "", new byte[0], List.of());
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
