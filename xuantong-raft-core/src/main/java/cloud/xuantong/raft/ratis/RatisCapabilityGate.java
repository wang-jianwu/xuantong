package cloud.xuantong.raft.ratis;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed capability gate used before voter promotion or format activation. */
public final class RatisCapabilityGate {
    private RatisCapabilityGate() {
    }

    public static void requireSupported(
            Collection<RatisPeerDefinition> voters,
            RatisVersionRequirement requirement,
            Collection<RatisStateNodeCapability> capabilities) {
        if (voters == null || voters.isEmpty()) {
            throw new IllegalArgumentException("voters must not be empty");
        }
        if (requirement == null) {
            throw new IllegalArgumentException("requirement must not be null");
        }
        Map<String, RatisStateNodeCapability> byNode = new LinkedHashMap<>();
        for (RatisStateNodeCapability capability
                : capabilities == null ? List.<RatisStateNodeCapability>of() : capabilities) {
            if (!requirement.groupId().equals(capability.groupId())) {
                continue;
            }
            if (byNode.putIfAbsent(capability.nodeId(), capability) != null) {
                throw new IllegalArgumentException(
                        "Duplicate capability for State node " + capability.nodeId());
            }
        }
        for (RatisPeerDefinition voter : voters) {
            RatisStateNodeCapability capability = byNode.get(voter.nodeId());
            if (capability == null) {
                throw new IllegalStateException(
                        "State node did not report a capability for "
                                + requirement.groupId() + ": " + voter.nodeId());
            }
            if (!capability.supports(requirement)) {
                throw new IllegalStateException(
                        "State node " + voter.nodeId() + " cannot activate "
                                + requirement + "; reported=" + capability);
            }
        }
    }
}
