package cloud.xuantong.core;

import cloud.xuantong.core.v2.model.ConfigResourceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigResourceKeyTest {
    @Test
    void buildsCanonicalResourceName() {
        ConfigResourceKey key = ConfigResourceKey.of("mall-prod", "PAYMENT_GROUP", "payment.yml");
        assertEquals("mall-prod/PAYMENT_GROUP/payment.yml", key.canonicalName());
    }

    @Test
    void usesDefaultGroup() {
        ConfigResourceKey key = ConfigResourceKey.inDefaultGroup("public", "application.yml");
        assertEquals("DEFAULT_GROUP", key.groupName());
    }

    @Test
    void rejectsInvalidSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigResourceKey.of("mall/prod", "DEFAULT_GROUP", "application.yml"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigResourceKey.of("public", "DEFAULT GROUP", "application.yml"));
    }
}
