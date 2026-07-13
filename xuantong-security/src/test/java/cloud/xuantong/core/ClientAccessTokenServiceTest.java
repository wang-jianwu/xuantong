package cloud.xuantong.core;

import cloud.xuantong.core.model.ClientAccessToken;
import cloud.xuantong.core.repository.ClientAccessTokenRepository;
import cloud.xuantong.core.service.ClientAccessTokenService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientAccessTokenServiceTest {
    @Test
    void storesOnlyHashAndEnforcesScope() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        ClientAccessTokenService service = new ClientAccessTokenService();
        inject(service, "repository", repository);
        inject(service, "authRequired", true);

        ClientAccessTokenService.IssuedToken issued = service.issue(
                "payment", "prod", "PAYMENT", null, "admin");

        assertTrue(issued.rawToken().startsWith("xt_"));
        assertNotEquals(issued.rawToken(), issued.token().getTokenHash());
        assertEquals(64, issued.token().getTokenHash().length());
        assertTrue(service.authorize(issued.rawToken(), "prod", "PAYMENT"));
        assertTrue(service.isAuthorizedFingerprint(
                issued.token().getTokenHash(), "prod", "PAYMENT"));
        assertFalse(service.authorize(issued.rawToken(), "prod", "ORDER"));
        assertFalse(service.authorize("wrong", "prod", "PAYMENT"));
        assertTrue(service.revoke(issued.token().getId()));
        assertFalse(service.authorize(issued.rawToken(), "prod", "PAYMENT"));
        assertEquals(1L, service.issuedTotal());
        assertEquals(1L, service.revokedTotal());
        assertEquals(1L, service.authSuccessTotal());
        assertEquals(3L, service.authFailureTotal());
    }

    private void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class MemoryRepository implements ClientAccessTokenRepository {
        private final List<ClientAccessToken> tokens = new ArrayList<>();
        public ClientAccessToken find(Long id) { return tokens.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null); }
        public ClientAccessToken findByHash(String hash) { return tokens.stream().filter(t -> t.getTokenHash().equals(hash)).findFirst().orElse(null); }
        public List<ClientAccessToken> findAll() { return tokens; }
        public long save(ClientAccessToken token) { token.setId((long) tokens.size() + 1); tokens.add(token); return 1; }
        public long revoke(Long id) { ClientAccessToken token = tokens.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null); if (token == null) return 0; token.setIsActive(false); return 1; }
        public long countActive() { return tokens.stream().filter(t -> Boolean.TRUE.equals(t.getIsActive())).count(); }
    }
}
