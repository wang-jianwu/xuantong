package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;

/** Version set currently active for one State Group. */
public record RatisVersionRequirement(
        StateGroupId groupId,
        int envelopeVersion,
        int commandSchemaVersion,
        int snapshotSchemaVersion) {

    public RatisVersionRequirement {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (envelopeVersion < 1 || commandSchemaVersion < 1
                || snapshotSchemaVersion < 1) {
            throw new IllegalArgumentException("Active State versions must be positive");
        }
    }
}
