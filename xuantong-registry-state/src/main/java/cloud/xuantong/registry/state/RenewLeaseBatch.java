package cloud.xuantong.registry.state;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record RenewLeaseBatch(
        RegistryActor actor,
        List<LeaseRenewal> renewals,
        long observedTimeEpochMs) implements RegistryMutation {

    public RenewLeaseBatch {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        renewals = List.copyOf(renewals == null ? List.of() : renewals);
        if (renewals.isEmpty()) {
            throw new IllegalArgumentException("renewals must not be empty");
        }
        Set<InstanceKey> keys = new HashSet<>();
        for (LeaseRenewal renewal : renewals) {
            if (!keys.add(renewal.lease().instanceKey())) {
                throw new IllegalArgumentException("renewals contain a duplicate instance");
            }
        }
        if (observedTimeEpochMs < 0) {
            throw new IllegalArgumentException("observedTimeEpochMs must not be negative");
        }
    }
}
