package cloud.xuantong.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientIdentityTest {
    @Test
    void generatesOneRuntimeInstanceIdForTheSameApplication() {
        ClientIdentity first = new ClientIdentity("order-service", null);
        ClientIdentity second = new ClientIdentity("order-service", "");

        assertFalse(first.getClientInstanceId().trim().isEmpty());
        assertEquals(first.getClientInstanceId(), second.getClientInstanceId());
    }

    @Test
    void explicitInstanceIdHasHighestPriority() {
        ClientIdentity identity = new ClientIdentity("order-service", "order-service@node-a:8081");

        assertEquals("order-service", identity.getApplicationName());
        assertEquals("order-service@node-a:8081", identity.getClientInstanceId());
    }
}
