package cloud.xuantong.probe;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProbeSettingsTest {
    @Test
    void environmentBuildsDiscoveryProfileWithoutExposingToken() {
        ProbeSettings settings = ProbeSettings.fromEnvironment(Map.of(
                "XUANTONG_PROBE_SERVERS", "gateway-a:8090, gateway-b:8090,gateway-a:8090",
                "XUANTONG_PROBE_PROFILE", "discovery",
                "XUANTONG_PROBE_TOKEN", "top-secret",
                "XUANTONG_PROBE_NAMESPACE", "tenant-a",
                "XUANTONG_PROBE_GROUP", "PAYMENT",
                "XUANTONG_PROBE_TRANSPORT_GENERATION", "7",
                "XUANTONG_PROBE_PORT", "9218"));

        assertEquals(ProbeSettings.Profile.DISCOVERY, settings.profile());
        assertEquals(2, settings.servers().size());
        assertEquals("registry-default", settings.controlPlane().stateGroupId());
        assertEquals(7L, settings.controlPlane().transportGeneration());
        assertEquals(9_218, settings.port());
        assertFalse(settings.toString().contains("top-secret"));
    }

    @Test
    void invalidProfileAndTimeoutAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProbeSettings.fromEnvironment(Map.of(
                        "XUANTONG_PROBE_PROFILE", "both")));
        assertThrows(IllegalArgumentException.class,
                () -> ProbeSettings.fromEnvironment(Map.of(
                        "XUANTONG_PROBE_REQUEST_TIMEOUT_MS", "20")));
    }
}
