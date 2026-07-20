package cloud.xuantong.server.admin.security;

import cloud.xuantong.security.model.User;
import cloud.xuantong.security.repository.UserRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

@Component
public class AdminSessionService {
    public static final String SESSION_COOKIE = "XUANTONG_ADMIN_SESSION";
    public static final String CSRF_COOKIE = "XUANTONG_CSRF";
    public static final String CSRF_HEADER = "X-Xuantong-CSRF";

    private static final String CLAIMS_ATTRIBUTE = "xuantong.admin.session.claims";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    @Inject
    private AdminSecurityProperties properties;
    @Inject
    private UserRepository userRepository;

    private Clock clock = Clock.systemUTC();
    private SecureRandom random = new SecureRandom();

    public AdminSessionService() {
    }

    AdminSessionService(AdminSecurityProperties properties, UserRepository userRepository,
                        Clock clock, SecureRandom random) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.clock = clock;
        this.random = random;
    }

    public String issue(Context context, User user) {
        if (user == null || user.getId() == null || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalArgumentException("An active user is required");
        }
        long now = clock.millis();
        long expiresAt = Math.addExact(now, Math.multiplyExact(
                properties.sessionTtlSeconds(), 1_000L));
        String csrfToken = randomToken(32);
        Claims claims = new Claims(
                user.getId(), securityVersion(user), now, expiresAt,
                sha256Base64(csrfToken), randomToken(18));
        String sessionToken = sign(claims);
        writeCookie(context, SESSION_COOKIE, sessionToken,
                properties.sessionTtlSeconds(), true);
        writeCookie(context, CSRF_COOKIE, csrfToken,
                properties.sessionTtlSeconds(), false);
        context.attrSet(CLAIMS_ATTRIBUTE, claims);
        AdminSecurityContext.bind(context, user);
        return csrfToken;
    }

    public User authenticate(Context context) {
        String token = context.cookie(SESSION_COOKIE);
        Claims claims = verify(token);
        if (claims == null || claims.expiresAtEpochMs() <= clock.millis()) {
            if (token != null) {
                clear(context);
            }
            return null;
        }
        User user = userRepository.findById(claims.userId());
        if (user == null
                || !Boolean.TRUE.equals(user.getIsActive())
                || securityVersion(user) != claims.securityVersion()) {
            clear(context);
            return null;
        }
        context.attrSet(CLAIMS_ATTRIBUTE, claims);
        AdminSecurityContext.bind(context, user);
        return user;
    }

    public boolean validateCsrf(Context context) {
        Claims claims = context.attr(CLAIMS_ATTRIBUTE);
        if (claims == null) {
            claims = verify(context.cookie(SESSION_COOKIE));
        }
        String cookieToken = context.cookie(CSRF_COOKIE);
        String headerToken = context.header(CSRF_HEADER);
        if (claims == null || cookieToken == null || headerToken == null) {
            return false;
        }
        return constantEquals(cookieToken, headerToken)
                && constantEquals(claims.csrfHash(), sha256Base64(headerToken));
    }

    public void clear(Context context) {
        writeCookie(context, SESSION_COOKIE, "", 0, true);
        writeCookie(context, CSRF_COOKIE, "", 0, false);
        context.attrMap().remove(CLAIMS_ATTRIBUTE);
        context.attrMap().remove(AdminSecurityContext.USER_ATTRIBUTE);
    }

    Claims verify(String token) {
        if (token == null || token.isBlank() || token.length() > 2_048) {
            return null;
        }
        int separator = token.indexOf('.');
        if (separator < 1 || separator == token.length() - 1
                || token.indexOf('.', separator + 1) >= 0) {
            return null;
        }
        String encodedPayload = token.substring(0, separator);
        String encodedSignature = token.substring(separator + 1);
        byte[] expected = hmac(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        byte[] actual;
        try {
            actual = BASE64_DECODER.decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return null;
        }
        try {
            String payload = new String(BASE64_DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 7 || !"1".equals(parts[0])) {
                return null;
            }
            return new Claims(
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]),
                    parts[5],
                    parts[6]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sign(Claims claims) {
        String payload = String.join("|",
                "1",
                Long.toString(claims.userId()),
                Long.toString(claims.securityVersion()),
                Long.toString(claims.issuedAtEpochMs()),
                Long.toString(claims.expiresAtEpochMs()),
                claims.csrfHash(),
                claims.nonce());
        String encodedPayload = BASE64_ENCODER.encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + BASE64_ENCODER.encodeToString(
                hmac(encodedPayload.getBytes(StandardCharsets.US_ASCII)));
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingKey(), "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign admin session", e);
        }
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return BASE64_ENCODER.encodeToString(value);
    }

    private String sha256Base64(String value) {
        try {
            return BASE64_ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private void writeCookie(Context context, String name, String value,
                             long maxAgeSeconds, boolean httpOnly) {
        StringBuilder cookie = new StringBuilder(name)
                .append('=').append(value)
                .append("; Path=/; Max-Age=").append(maxAgeSeconds)
                .append("; SameSite=").append(properties.cookieSameSite());
        if (httpOnly) {
            cookie.append("; HttpOnly");
        }
        if (properties.cookieSecure()) {
            cookie.append("; Secure");
        }
        context.headerAdd("Set-Cookie", cookie.toString());
    }

    private long securityVersion(User user) {
        return user.getSecurityVersion() == null ? 1L : user.getSecurityVersion();
    }

    record Claims(long userId, long securityVersion, long issuedAtEpochMs,
                  long expiresAtEpochMs, String csrfHash, String nonce) {
    }
}
