package cloud.xuantong.server.admin.security;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.ContextEmpty;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSessionServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    @Test
    void signedSessionWorksAcrossServersAndIsBoundToCsrfToken() {
        MemoryUsers users = new MemoryUsers(activeUser());
        AdminSecurityProperties properties = properties();
        AdminSessionService serverOne = new AdminSessionService(
                properties, users, CLOCK, new SecureRandom());

        ContextEmpty login = new ContextEmpty();
        String csrf = serverOne.issue(login, users.user);
        Map<String, String> cookies = responseCookies(login);

        assertNotNull(cookies.get(AdminSessionService.SESSION_COOKIE));
        assertNotNull(cookies.get(AdminSessionService.CSRF_COOKIE));
        assertTrue(cookieHeaders(login).stream().anyMatch(value ->
                value.startsWith(AdminSessionService.SESSION_COOKIE + "=")
                        && value.contains("; HttpOnly")
                        && value.contains("; Secure")
                        && value.contains("; SameSite=Strict")));
        assertTrue(cookieHeaders(login).stream().anyMatch(value ->
                value.startsWith(AdminSessionService.CSRF_COOKIE + "=")
                        && !value.contains("; HttpOnly")));

        AdminSessionService serverTwo = new AdminSessionService(
                properties, users, CLOCK, new SecureRandom());
        ContextEmpty request = requestWith(cookies, csrf);
        User authenticated = serverTwo.authenticate(request);
        assertNotNull(authenticated);
        assertTrue(serverTwo.validateCsrf(request));

        request.headerMap().put(AdminSessionService.CSRF_HEADER, csrf + "-tampered");
        assertFalse(serverTwo.validateCsrf(request));
    }

    @Test
    void securityVersionAndActiveStateInvalidateExistingSession() {
        MemoryUsers users = new MemoryUsers(activeUser());
        AdminSessionService service = new AdminSessionService(
                properties(), users, CLOCK, new SecureRandom());
        ContextEmpty login = new ContextEmpty();
        String csrf = service.issue(login, users.user);
        Map<String, String> cookies = responseCookies(login);

        users.user.setSecurityVersion(2L);
        ContextEmpty versionChanged = requestWith(cookies, csrf);
        assertNull(service.authenticate(versionChanged));
        assertTrue(cookieHeaders(versionChanged).stream().allMatch(value ->
                value.contains("Max-Age=0")));

        users.user.setSecurityVersion(1L);
        users.user.setIsActive(false);
        assertNull(service.authenticate(requestWith(cookies, csrf)));
    }

    private AdminSecurityProperties properties() {
        return new AdminSecurityProperties(
                "0123456789abcdef0123456789abcdef",
                7_200L,
                true,
                "Strict",
                5,
                900L,
                1L,
                300L);
    }

    private User activeUser() {
        User user = new User();
        user.setId(7L);
        user.setUsername("admin");
        user.setRole("SYSTEM_ADMIN");
        user.setIsActive(true);
        user.setSecurityVersion(1L);
        return user;
    }

    private ContextEmpty requestWith(Map<String, String> cookies, String csrf) {
        ContextEmpty context = new ContextEmpty();
        cookies.forEach(context.cookieMap()::put);
        context.headerMap().put(AdminSessionService.CSRF_HEADER, csrf);
        return context;
    }

    private Map<String, String> responseCookies(ContextEmpty context) {
        Map<String, String> cookies = new HashMap<>();
        for (String header : cookieHeaders(context)) {
            String pair = header.substring(0, header.indexOf(';'));
            int equals = pair.indexOf('=');
            cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return cookies;
    }

    private Collection<String> cookieHeaders(ContextEmpty context) {
        return context.headerValuesOfResponse("Set-Cookie");
    }

    private static class MemoryUsers implements UserRepository {
        private final User user;

        private MemoryUsers(User user) {
            this.user = user;
        }

        @Override public User findByUsername(String username) { return user; }
        @Override public User findByEmail(String email) { return null; }
        @Override public List<User> findAll() { return List.of(user); }
        @Override public PageResult<User> findPage(
                String keyword, String role, Boolean active, PageQuery pageQuery) {
            boolean matches = (keyword == null || keyword.isBlank()
                    || user.getUsername().contains(keyword))
                    && (role == null || role.isBlank() || role.equals(user.getRole()))
                    && (active == null || active.equals(user.getIsActive()));
            List<User> items = matches && pageQuery.page() == 1 ? List.of(user) : List.of();
            return PageResult.of(pageQuery, matches ? 1 : 0, items);
        }
        @Override public long save(User value) { return 1; }
        @Override public long update(User value) { return 1; }
        @Override public long updateLoginTime(Long userId) { return 1; }
        @Override public long setActive(Long userId, boolean isActive) {
            user.setIsActive(isActive);
            user.setSecurityVersion(user.getSecurityVersion() + 1L);
            return 1;
        }
        @Override public long incrementSecurityVersion(Long userId) {
            user.setSecurityVersion(user.getSecurityVersion() + 1L);
            return 1;
        }
        @Override public User findById(Long id) { return user.getId().equals(id) ? user : null; }
        @Override public long delete(Long id) { return 0; }
        @Override public long countAll() { return 1; }
    }
}
