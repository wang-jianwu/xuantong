package cloud.xuantong.security;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.repository.UserRepository;
import cloud.xuantong.security.service.UserService;
import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {
    @Test
    void disabledUserCannotAuthenticate() throws Exception {
        User user = user();
        user.setIsActive(false);
        UserService service = service(new MemoryUsers(user));
        assertNull(service.authenticate("admin", "correct-password"));
    }

    @Test
    void activeUserAuthenticatesWithBcrypt() throws Exception {
        User user = user();
        MemoryUsers repository = new MemoryUsers(user);
        UserService service = service(repository);
        assertSame(user, service.authenticate("admin", "correct-password"));
        assertEquals(1L, repository.loginUpdates);
    }

    @Test
    void roleAndPasswordChangesAdvanceSecurityVersion() throws Exception {
        User existing = user();
        MemoryUsers repository = new MemoryUsers(existing);
        UserService service = service(repository);

        User changes = new User();
        changes.setId(existing.getId());
        changes.setRole("DEVELOPER");
        changes.setEmail("new@example.com");
        changes.setRealName("New Name");
        changes.setPassword("new-password");

        assertTrue(service.updateUser(changes));
        assertEquals("DEVELOPER", existing.getRole());
        assertEquals(2L, existing.getSecurityVersion());
        assertTrue(BCrypt.checkpw("new-password", existing.getPassword()));
    }

    private UserService service(UserRepository repository) throws Exception {
        UserService service = new UserService();
        Field field = UserService.class.getDeclaredField("userRepository");
        field.setAccessible(true);
        field.set(service, repository);
        return service;
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(BCrypt.hashpw("correct-password", BCrypt.gensalt(4)));
        user.setEmail("admin@example.com");
        user.setRealName("Admin");
        user.setRole("SYSTEM_ADMIN");
        user.setIsActive(true);
        user.setSecurityVersion(1L);
        return user;
    }

    private static class MemoryUsers implements UserRepository {
        private final User user;
        private long loginUpdates;

        private MemoryUsers(User user) {
            this.user = user;
        }

        @Override public User findByUsername(String username) {
            return user.getUsername().equals(username) ? user : null;
        }
        @Override public User findByEmail(String email) { return null; }
        @Override public List<User> findAll() { return List.of(user); }
        @Override public PageResult<User> findPage(
                String keyword, String role, Boolean active, PageQuery pageQuery) {
            boolean matches = (keyword == null || keyword.isBlank()
                    || user.getUsername().contains(keyword))
                    && (role == null || role.isBlank() || role.equals(user.getRole()))
                    && (active == null || active.equals(user.getIsActive()));
            return PageResult.of(pageQuery, matches ? 1 : 0,
                    matches && pageQuery.page() == 1 ? List.of(user) : List.of());
        }
        @Override public long save(User value) { return 1; }
        @Override public long update(User value) { return 1; }
        @Override public long updateLoginTime(Long userId) { loginUpdates++; return 1; }
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
