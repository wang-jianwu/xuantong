package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateMachineCompatibility;

/** Runtime capability reported directly by one Raft division. */
public record RatisStateNodeCapability(
        String nodeId,
        StateGroupId groupId,
        String implementationVersion,
        int minimumEnvelopeVersion,
        int maximumEnvelopeVersion,
        StateMachineCompatibility stateMachine) {

    public RatisStateNodeCapability {
        nodeId = required("nodeId", nodeId);
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        implementationVersion = required(
                "implementationVersion", implementationVersion);
        if (minimumEnvelopeVersion < 1
                || maximumEnvelopeVersion < minimumEnvelopeVersion) {
            throw new IllegalArgumentException("State envelope version range is invalid");
        }
        if (stateMachine == null) {
            throw new IllegalArgumentException("stateMachine must not be null");
        }
    }

    public boolean supports(RatisVersionRequirement requirement) {
        return groupId.equals(requirement.groupId())
                && requirement.envelopeVersion() >= minimumEnvelopeVersion
                && requirement.envelopeVersion() <= maximumEnvelopeVersion
                && stateMachine.supportsCommand(requirement.commandSchemaVersion())
                && stateMachine.canReadSnapshot(requirement.snapshotSchemaVersion());
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
