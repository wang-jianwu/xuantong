package cloud.xuantong.core.service;
import cloud.xuantong.core.event.ClientAccessTokenRevokedEvent;
import cloud.xuantong.core.model.ClientAccessToken;
import cloud.xuantong.core.repository.ClientAccessTokenRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventBus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
@Component
public class ClientAccessTokenService {
    @Inject private ClientAccessTokenRepository repository;
    @Inject("${security.clientAuthRequired:false}") private boolean authRequired;
    private final AtomicLong authSuccessTotal = new AtomicLong();
    private final AtomicLong authFailureTotal = new AtomicLong();
    private final AtomicLong issuedTotal = new AtomicLong();
    private final AtomicLong revokedTotal = new AtomicLong();
    public IssuedToken issue(String name, String namespaceId, String groupName, Date expiresAt, String operator) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("tokenName must not be blank");
        String raw = generateToken();
        ClientAccessToken token = new ClientAccessToken();
        token.setTokenName(name.trim()); token.setTokenHash(hash(raw));
        token.setNamespaceId(scope(namespaceId)); token.setGroupName(scope(groupName));
        token.setIsActive(true); token.setCreatedBy(operator); token.setCreatedAt(new Date()); token.setExpiresAt(expiresAt);
        if (repository.save(token) != 1) throw new IllegalStateException("Failed to create access token");
        issuedTotal.incrementAndGet();
        return new IssuedToken(raw, token);
    }
    public boolean authorize(String raw, String namespaceId, String groupName) {
        boolean allowed = isAuthorized(raw, namespaceId, groupName);
        recordAuthorization(allowed);
        return allowed;
    }
    public boolean isAuthorized(String raw, String namespaceId, String groupName) {
        if (raw == null || raw.isBlank()) {
            return !authRequired && repository.countActive() == 0;
        }
        return isAuthorizedFingerprint(hash(raw), namespaceId, groupName);
    }
    public boolean isAuthorizedFingerprint(String tokenHash, String namespaceId, String groupName) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return !authRequired && repository.countActive() == 0;
        }
        ClientAccessToken token = repository.findByHash(tokenHash);
        return token != null && Boolean.TRUE.equals(token.getIsActive())
                && (token.getExpiresAt() == null || !token.getExpiresAt().before(new Date()))
                && matches(token.getNamespaceId(), namespaceId) && matches(token.getGroupName(), groupName);
    }
    public List<ClientAccessToken> findAll() { return repository.findAll(); }
    public boolean revoke(Long id) {
        ClientAccessToken token = repository.find(id);
        if (token == null) return false;
        boolean revoked = repository.revoke(id) == 1;
        if (revoked) {
            revokedTotal.incrementAndGet();
            EventBus.publish(new ClientAccessTokenRevokedEvent(token.getTokenHash()));
        }
        return revoked;
    }
    public String fingerprint(String raw) { return raw == null || raw.isBlank() ? "" : hash(raw); }
    private void recordAuthorization(boolean allowed) { (allowed ? authSuccessTotal : authFailureTotal).incrementAndGet(); }
    public long authSuccessTotal() { return authSuccessTotal.get(); }
    public long authFailureTotal() { return authFailureTotal.get(); }
    public long issuedTotal() { return issuedTotal.get(); }
    public long revokedTotal() { return revokedTotal.get(); }
    private boolean matches(String scope, String value) { return "*".equals(scope) || scope.equals(value); }
    private String scope(String value) { return value == null || value.isBlank() ? "*" : value.trim(); }
    private String generateToken() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return "xt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private String hash(String raw) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    public record IssuedToken(String rawToken, ClientAccessToken token) {}
}
