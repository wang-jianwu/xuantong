package cloud.xuantong.config.management;

import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigRolloutPolicyTest {
    @Test
    void normalizesIpTargets() {
        ConfigRolloutPolicy policy = ConfigRolloutPolicy.ip(
                List.of("10.0.0.8", "2001:db8::1", "10.0.0.8"));

        assertEquals(2, policy.ipTargets().size());
        assertEquals("10.0.0.8,2001:db8:0:0:0:0:0:1", policy.targetValue());
        assertThrows(IllegalArgumentException.class,
                () -> ConfigRolloutPolicy.ip(List.of("example.com")));
    }

    @Test
    void validatesPercentageRange() {
        ConfigRolloutPolicy policy = ConfigRolloutPolicy.percentage(35);
        assertEquals(35, policy.percentage());
        assertThrows(IllegalArgumentException.class, () -> ConfigRolloutPolicy.percentage(0));
        assertThrows(IllegalArgumentException.class, () -> ConfigRolloutPolicy.percentage(100));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigRolloutPolicy(
                        cloud.xuantong.config.management.model.ReleaseType.GRAY_PERCENTAGE,
                        "invalid"));
    }

    @Test
    void exactClientInstanceTargetsAreCanonicalAndBounded() {
        ConfigRolloutPolicy policy = ConfigRolloutPolicy.clientInstances(
                List.of("instance-b", "instance-a", "instance-a"));

        assertEquals(2, policy.clientInstanceTargets().size());
        assertEquals("instance-a,instance-b", policy.targetValue());
        assertThrows(IllegalArgumentException.class,
                () -> ConfigRolloutPolicy.clientInstances(List.of(" ")));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigRolloutPolicy.clientInstances(List.of("instance,a")));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigRolloutPolicy.clientInstances(List.of("x".repeat(257))));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigRolloutPolicy(
                        cloud.xuantong.config.management.model.ReleaseType.GRAY_CLIENT_INSTANCE,
                        "instance-a,"));
    }
}
