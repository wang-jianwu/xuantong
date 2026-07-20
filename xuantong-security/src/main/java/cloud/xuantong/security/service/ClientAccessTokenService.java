package cloud.xuantong.security.service;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.event.ClientAccessTokenRevokedEvent;
import cloud.xuantong.security.model.ClientAccessToken;
import cloud.xuantong.security.repository.ClientAccessTokenRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventBus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ClientAccessTokenService {
    private static final String DEFAULT_TENANT = "default";

    @Inject
    private ClientAccessTokenRepository repository;
    @Inject("${security.clientAuthRequired:false}")
    private boolean authRequired;

    private final AtomicLong authSuccessTotal = new AtomicLong();
    private final AtomicLong authFailureTotal = new AtomicLong();
    private final AtomicLong issuedTotal = new AtomicLong();
    private final AtomicLong revokedTotal = new AtomicLong();

    public IssuedToken issue(
            String name,
            String namespaceId,
            String groupName,
            Date expiresAt,
            String operator) {
        return issue(name, DEFAULT_TENANT, namespaceId, groupName, expiresAt, operator);
    }

    public IssuedToken issue(
            String name,
            String tenant,
            String namespaceId,
            String groupName,
            Date expiresAt,
            String operator) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tokenName must not be blank");
        }
        if (expiresAt != null && !expiresAt.after(new Date())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        String raw = generateToken();
        ClientAccessToken token = new ClientAccessToken();
        token.setTokenName(name.trim());
        token.setTokenHash(hash(raw));
        token.setTenant(scope(tenant, DEFAULT_TENANT));
        token.setNamespaceId(scope(namespaceId, "*"));
        token.setGroupName(scope(groupName, "*"));
        token.setIsActive(true);
        token.setCreatedBy(operator);
        token.setCreatedAt(new Date());
        token.setExpiresAt(expiresAt);
        if (repository.save(token) != 1) {
            throw new IllegalStateException("Failed to create access token");
        }
        issuedTotal.incrementAndGet();
        return new IssuedToken(raw, token);
    }

    public boolean authorize(String raw, String namespaceId, String groupName) {
        return authorize(raw, DEFAULT_TENANT, namespaceId, groupName);
    }

    public boolean authorize(
            String raw, String tenant, String namespaceId, String groupName) {
        boolean allowed = authenticate(raw, tenant, namespaceId, groupName) != null;
        recordAuthorization(allowed);
        return allowed;
    }

    public boolean isAuthorized(String raw, String namespaceId, String groupName) {
        return authenticate(raw, DEFAULT_TENANT, namespaceId, groupName) != null;
    }

    public boolean isAuthorizedFingerprint(
            String tokenHash, String namespaceId, String groupName) {
        return authenticateFingerprint(
                tokenHash, DEFAULT_TENANT, namespaceId, groupName) != null;
    }

    public AuthenticatedToken authenticate(
            String raw, String tenant, String namespaceId, String groupName) {
        if (raw == null || raw.isBlank()) {
            return anonymousAllowed(tenant, namespaceId, groupName);
        }
        return authenticateFingerprint(hash(raw), tenant, namespaceId, groupName);
    }

    public AuthenticatedToken authenticateAndRecord(
            String raw, String tenant, String namespaceId, String groupName) {
        AuthenticatedToken authenticated = authenticate(
                raw, tenant, namespaceId, groupName);
        recordAuthorization(authenticated != null);
        return authenticated;
    }

    public AuthenticatedToken authenticateFingerprint(
            String tokenHash,
            String tenant,
            String namespaceId,
            String groupName) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return anonymousAllowed(tenant, namespaceId, groupName);
        }
        ClientAccessToken token = repository.findByHash(tokenHash);
        if (token == null
                || !Boolean.TRUE.equals(token.getIsActive())
                || (token.getExpiresAt() != null
                && !token.getExpiresAt().after(new Date()))
                || !matches(token.getTenant(), tenant)
                || !matches(token.getNamespaceId(), namespaceId)
                || !matches(token.getGroupName(), groupName)) {
            return null;
        }
        return new AuthenticatedToken(
                "client-token:" + token.getId(),
                token.getTokenName(),
                token.getTokenHash(),
                normalizedScopeValue(tenant, DEFAULT_TENANT),
                normalizedScopeValue(namespaceId, ""),
                normalizedScopeValue(groupName, ""),
                token.getExpiresAt() == null ? 0L : token.getExpiresAt().getTime(),
                false);
    }

    public List<ClientAccessToken> findAll() {
        return repository.findAll();
    }

    public PageResult<ClientAccessToken> findPage(
            String keyword, Boolean active, PageQuery pageQuery) {
        return repository.findPage(keyword, active, pageQuery);
    }

    public boolean revoke(Long id) {
        ClientAccessToken token = repository.find(id);
        if (token == null) {
            return false;
        }
        boolean revoked = repository.revoke(id) == 1;
        if (revoked) {
            revokedTotal.incrementAndGet();
            EventBus.publish(new ClientAccessTokenRevokedEvent(token.getTokenHash()));
        }
        return revoked;
    }

    public String fingerprint(String raw) {
        return raw == null || raw.isBlank() ? "" : hash(raw);
    }

    public boolean authRequired() {
        return authRequired;
    }

    private AuthenticatedToken anonymousAllowed(
            String tenant, String namespaceId, String groupName) {
        if (authRequired || repository.countActive() > 0) {
            return null;
        }
        return new AuthenticatedToken(
                "anonymous",
                "anonymous",
                "",
                normalizedScopeValue(tenant, DEFAULT_TENANT),
                normalizedScopeValue(namespaceId, ""),
                normalizedScopeValue(groupName, ""),
                0L,
                true);
    }

    private void recordAuthorization(boolean allowed) {
        (allowed ? authSuccessTotal : authFailureTotal).incrementAndGet();
    }

    public long authSuccessTotal() {
        return authSuccessTotal.get();
    }

    public long authFailureTotal() {
        return authFailureTotal.get();
    }

    public long issuedTotal() {
        return issuedTotal.get();
    }

    public long revokedTotal() {
        return revokedTotal.get();
    }

    private boolean matches(String scope, String value) {
        return "*".equals(scope)
                || (scope != null && scope.equals(normalizedScopeValue(value, "")));
    }

    private String scope(String value, String defaultValue) {
        String normalized = normalizedScopeValue(value, defaultValue);
        if (!"*".equals(normalized)
                && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Token scope is invalid: " + normalized);
        }
        return normalized;
    }

    private String normalizedScopeValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "xt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record IssuedToken(String rawToken, ClientAccessToken token) {
    }

    public record AuthenticatedToken(
            String principalId,
            String tokenName,
            String fingerprint,
            String tenant,
            String namespaceId,
            String groupName,
            long expiresAtEpochMs,
            boolean anonymous) {
    }
}
