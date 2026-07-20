package cloud.xuantong.config.management.service;

import java.util.regex.Pattern;

/** Redacts credentials and configuration bodies before audit persistence or response. */
public final class AuditDetailSanitizer {
    private static final String SENSITIVE_KEY =
            "password|passwd|secret|accessToken|rawToken|tokenHash|authorization|cookie|"
                    + "privateKey|keyStore|keyStorePassword|trustStore|trustStorePassword|"
                    + "certificate|cert|content|oldContent|newContent|submittedContent|currentContent";
    private static final Pattern JSON_VALUE = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_KEY + ")\"\\s*:\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|[^,}\\s]+)");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(" + SENSITIVE_KEY + ")\\b(\\s*[=:]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^,;\\s]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern XUANTONG_TOKEN = Pattern.compile(
            "\\bxt_[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "(?is)-----BEGIN [^-\\r\\n]+-----.*?-----END [^-\\r\\n]+-----");

    private AuditDetailSanitizer() {
    }

    public static String sanitize(String detail) {
        if (detail == null || detail.isBlank()) {
            return detail;
        }
        String sanitized = PEM_BLOCK.matcher(detail).replaceAll("***PEM REDACTED***");
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer ***");
        sanitized = XUANTONG_TOKEN.matcher(sanitized).replaceAll("xt_***");
        sanitized = JSON_VALUE.matcher(sanitized).replaceAll("$1\"***\"");
        sanitized = KEY_VALUE.matcher(sanitized).replaceAll("$1$2***");
        return XUANTONG_TOKEN.matcher(sanitized).replaceAll("xt_***");
    }
}
