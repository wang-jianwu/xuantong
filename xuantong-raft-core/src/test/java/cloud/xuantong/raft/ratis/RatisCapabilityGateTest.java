package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateMachineCompatibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RatisCapabilityGateTest {
    private final StateGroupId groupId = StateGroupId.config("config-default");
    private final List<RatisPeerDefinition> voters = List.of(
            new RatisPeerDefinition("state-1", "127.0.0.1", 9101),
            new RatisPeerDefinition("state-2", "127.0.0.1", 9102),
            new RatisPeerDefinition("state-3", "127.0.0.1", 9103));

    @Test
    void requiresEveryTargetVoterToSupportActiveVersions() {
        RatisVersionRequirement requirement =
                new RatisVersionRequirement(groupId, 1, 2, 2);
        List<RatisStateNodeCapability> compatible = voters.stream()
                .map(peer -> capability(peer.nodeId(), 1, 2, 3, 2, 3))
                .toList();

        assertDoesNotThrow(() -> RatisCapabilityGate.requireSupported(
                voters, requirement, compatible));

        List<RatisStateNodeCapability> missing = compatible.subList(0, 2);
        assertThrows(IllegalStateException.class,
                () -> RatisCapabilityGate.requireSupported(
                        voters, requirement, missing));

        List<RatisStateNodeCapability> oldSnapshotReader = List.of(
                compatible.get(0), compatible.get(1),
                capability("state-3", 1, 2, 2, 1, 1));
        assertThrows(IllegalStateException.class,
                () -> RatisCapabilityGate.requireSupported(
                        voters, requirement, oldSnapshotReader));
    }

    private RatisStateNodeCapability capability(
            String nodeId,
            int envelope,
            int commandMin,
            int commandMax,
            int snapshotMin,
            int snapshotMax) {
        return new RatisStateNodeCapability(
                nodeId,
                groupId,
                "2.0.0-test",
                envelope,
                envelope,
                new StateMachineCompatibility(
                        commandMin, commandMax,
                        snapshotMin, snapshotMax, snapshotMin));
    }
}
