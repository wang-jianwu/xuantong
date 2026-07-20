package cloud.xuantong.server.admin.security;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

@Configuration
public class AdminSecurityProperties {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityProperties.class);

    private final byte[] developmentSigningKey = new byte[32];

    @Inject("${security.production:false}")
    private boolean production;
    @Inject("${security.admin.sessionSecret:}")
    private String sessionSecret;
    @Inject("${security.admin.sessionTtlSeconds:7200}")
    private long sessionTtlSeconds;
    @Inject("${security.admin.cookieSecure:false}")
    private boolean cookieSecure;
    @Inject("${security.admin.cookieSameSite:Lax}")
    private String cookieSameSite;
    @Inject("${security.admin.login.maxFailures:5}")
    private int loginMaxFailures;
    @Inject("${security.admin.login.windowSeconds:900}")
    private long loginWindowSeconds;
    @Inject("${security.admin.login.baseBackoffSeconds:1}")
    private long loginBaseBackoffSeconds;
    @Inject("${security.admin.login.maxBackoffSeconds:300}")
    private long loginMaxBackoffSeconds;

    public AdminSecurityProperties() {
        new SecureRandom().nextBytes(developmentSigningKey);
    }

    AdminSecurityProperties(String sessionSecret, long sessionTtlSeconds,
                            boolean cookieSecure, String cookieSameSite,
                            int loginMaxFailures, long loginWindowSeconds,
                            long loginBaseBackoffSeconds, long loginMaxBackoffSeconds) {
        this();
        this.sessionSecret = sessionSecret;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.loginMaxFailures = loginMaxFailures;
        this.loginWindowSeconds = loginWindowSeconds;
        this.loginBaseBackoffSeconds = loginBaseBackoffSeconds;
        this.loginMaxBackoffSeconds = loginMaxBackoffSeconds;
    }

    @Init(index = -900)
    public void validate() {
        signingKey();
        sessionTtlSeconds();
        loginMaxFailures();
        loginWindowMillis();
        loginBaseBackoffMillis();
        loginMaxBackoffMillis();
        cookieSameSite();
        if (production && !cookieSecure) {
            throw new IllegalStateException(
                    "security.admin.cookieSecure must be true in production");
        }
        if (!production && (sessionSecret == null || sessionSecret.isBlank())) {
            log.warn("security.admin.sessionSecret is empty; generated a process-local development key. "
                    + "Set XUANTONG_ADMIN_SESSION_SECRET for multi-Server sessions.");
        }
    }

    public byte[] signingKey() {
        if (sessionSecret == null || sessionSecret.isBlank()) {
            if (production) {
                throw new IllegalStateException(
                        "security.admin.sessionSecret is required in production");
            }
            return developmentSigningKey.clone();
        }
        byte[] key = sessionSecret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException(
                    "security.admin.sessionSecret must contain at least 32 UTF-8 bytes");
        }
        return key;
    }

    public long sessionTtlSeconds() {
        if (sessionTtlSeconds < 300 || sessionTtlSeconds > 86_400) {
            throw new IllegalStateException(
                    "security.admin.sessionTtlSeconds must be between 300 and 86400");
        }
        return sessionTtlSeconds;
    }

    public boolean cookieSecure() {
        return cookieSecure;
    }

    public String cookieSameSite() {
        String value = cookieSameSite == null
                ? "LAX"
                : cookieSameSite.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "LAX" -> "Lax";
            case "STRICT" -> "Strict";
            default -> throw new IllegalStateException(
                    "security.admin.cookieSameSite must be Lax or Strict");
        };
    }

    public int loginMaxFailures() {
        if (loginMaxFailures < 2 || loginMaxFailures > 100) {
            throw new IllegalStateException(
                    "security.admin.login.maxFailures must be between 2 and 100");
        }
        return loginMaxFailures;
    }

    public long loginWindowMillis() {
        return secondsToMillis("windowSeconds", loginWindowSeconds, 60, 86_400);
    }

    public long loginBaseBackoffMillis() {
        return secondsToMillis("baseBackoffSeconds", loginBaseBackoffSeconds, 1, 300);
    }

    public long loginMaxBackoffMillis() {
        long value = secondsToMillis("maxBackoffSeconds", loginMaxBackoffSeconds, 1, 3_600);
        if (value < loginBaseBackoffMillis()) {
            throw new IllegalStateException(
                    "security.admin.login.maxBackoffSeconds must not be smaller than baseBackoffSeconds");
        }
        return value;
    }

    private long secondsToMillis(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalStateException("security.admin.login." + name
                    + " must be between " + min + " and " + max);
        }
        return Math.multiplyExact(value, 1_000L);
    }
}
