package cloud.xuantong.config.management;

import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRolloutPolicyTest {
    @Test
    void normalizesIpTargetsAndMatchesObservedAddress() {
        ConfigRolloutPolicy policy = ConfigRolloutPolicy.ip(
                List.of("10.0.0.8", "2001:db8::1", "10.0.0.8"));

        assertEquals(2, policy.ipTargets().size());
        assertTrue(policy.matches("rollout-a", "client-a", "10.0.0.8"));
        assertTrue(policy.matches("rollout-a", "client-a", "2001:db8:0:0:0:0:0:1"));
        assertFalse(policy.matches("rollout-a", "client-a", "10.0.0.9"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigRolloutPolicy.ip(List.of("example.com")));
    }

    @Test
    void percentageUsesStableInstanceHashAndReasonableDistribution() {
        ConfigRolloutPolicy policy = ConfigRolloutPolicy.percentage(35);
        boolean first = policy.matches("rollout-a", "order-service@node-1", "10.0.0.8");
        assertEquals(first, ConfigRolloutPolicy.percentage(35)
                .matches("rollout-a", "order-service@node-1", null));
        for (int i = 0; i < 20; i++) {
            assertEquals(first,
                    policy.matches("rollout-a", "order-service@node-1", "192.168.1." + i));
        }

        int matched = 0;
        for (int i = 0; i < 10_000; i++) {
            if (policy.matches("rollout-a", "instance-" + i, null)) matched++;
        }
        assertTrue(matched > 3_300 && matched < 3_700, "matched=" + matched);
        assertFalse(policy.matches("rollout-a", null, null));

        ConfigRolloutPolicy expanded = ConfigRolloutPolicy.percentage(60);
        for (int i = 0; i < 1_000; i++) {
            String instanceId = "scaled-instance-" + i;
            if (policy.matches("rollout-a", instanceId, null)) {
                assertTrue(expanded.matches("rollout-a", instanceId, null));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigRolloutPolicy.percentage(0));
        assertThrows(IllegalArgumentException.class, () -> ConfigRolloutPolicy.percentage(100));
    }
}
