package cloud.xuantong.config.management;

import cloud.xuantong.config.management.service.AuditDetailSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditDetailSanitizerTest {
    @Test
    void redactsStructuredAndTextSecrets() {
        String detail = "{\"password\":\"p@ss\","
                + "\"accessToken\":\"xt_abcdefghijklmnop\","
                + "\"content\":\"db.password=secret\",\"contentRevision\":7} "
                + "Authorization=Bearer abc.def.ghi keyStorePassword=change-me "
                + "-----BEGIN " + "PRIVATE KEY-----\nprivate-material\n"
                + "-----END " + "PRIVATE KEY-----";

        String sanitized = AuditDetailSanitizer.sanitize(detail);

        assertFalse(sanitized.contains("p@ss"));
        assertFalse(sanitized.contains("abcdefghijklmnop"));
        assertFalse(sanitized.contains("db.password=secret"));
        assertFalse(sanitized.contains("abc.def.ghi"));
        assertFalse(sanitized.contains("change-me"));
        assertFalse(sanitized.contains("private-material"));
        assertTrue(sanitized.contains("contentRevision"));
    }
}
