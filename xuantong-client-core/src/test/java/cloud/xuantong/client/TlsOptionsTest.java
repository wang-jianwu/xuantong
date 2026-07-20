package cloud.xuantong.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsOptionsTest {
    @Test
    void defaultsAreStrictAndDisabled() {
        TlsOptions options = TlsOptions.disabled();
        assertTrue(options.hostnameVerification());
        assertEquals(30_000L, options.reloadIntervalMs());
        assertEquals("PKCS12", options.trustStoreType());
    }

    @Test
    void rejectsMisleadingOrUnboundedSettings() {
        assertThrows(IllegalArgumentException.class, () -> new TlsOptions(
                false, "/tmp/trust.p12", "PKCS12", "", "", "PKCS12", "", "",
                true, 30_000L));
        assertThrows(IllegalArgumentException.class, () -> new TlsOptions(
                true, "", "PKCS12", "", "", "PKCS12", "", "",
                true, 999L));
    }

    @Test
    void neverPrintsStorePasswords() {
        TlsOptions options = TlsOptions.enabled(
                "trust.p12", "PKCS12", "trust-secret",
                "client.p12", "PKCS12", "store-secret", "key-secret",
                true, 30_000L);
        String text = options.toString();
        assertTrue(!text.contains("trust-secret"));
        assertTrue(!text.contains("store-secret"));
        assertTrue(!text.contains("key-secret"));
    }
}
