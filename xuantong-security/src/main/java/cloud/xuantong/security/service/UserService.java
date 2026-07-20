package cloud.xuantong.security.service;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.model.ControlPlaneRole;
import cloud.xuantong.security.repository.UserRepository;
import cn.hutool.crypto.digest.BCrypt;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.List;
import java.util.Objects;

@Component
public class UserService {
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$nV1EGCqciAYfOzJY0/Nix.mr7oXQj70qYcOoNdENG07n8c8aGylda";

    @Inject
    private UserRepository userRepository;

    public User authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return null;
        }
        User user = userRepository.findByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            BCrypt.checkpw(password, DUMMY_PASSWORD_HASH);
            return null;
        }

        if (isBcrypt(user.getPassword()) && BCrypt.checkpw(password, user.getPassword())) {
            userRepository.updateLoginTime(user.getId());
            return user;
        }
        return null;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public PageResult<User> findPage(
            String keyword, String role, Boolean active, PageQuery pageQuery) {
        return userRepository.findPage(keyword, role, active, pageQuery);
    }

    public User getUserById(Long id) {
        return userRepository.findById(id);
    }

    public boolean createUser(User user) {
        normalizeRole(user);
        if (userRepository.findByUsername(user.getUsername()) != null) {
            throw new RuntimeException("用户名已存在");
        }
        if (userRepository.findByEmail(user.getEmail()) != null) {
            throw new RuntimeException("邮箱已存在");
        }

        user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt(12)));
        if (user.getIsActive() == null) {
            user.setIsActive(true);
        }
        user.setSecurityVersion(1L);
        return userRepository.save(user) > 0;
    }

    public boolean updateUser(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User id is required");
        }
        User existing = userRepository.findById(user.getId());
        if (existing == null) {
            return false;
        }

        String previousRole = existing.getRole();
        if (user.getRole() != null && !user.getRole().isBlank()) {
            existing.setRole(user.getRole());
            normalizeRole(existing);
        }
        existing.setEmail(user.getEmail());
        existing.setRealName(user.getRealName());

        boolean passwordChanged = user.getPassword() != null && !user.getPassword().isBlank();
        if (passwordChanged) {
            existing.setPassword(isBcrypt(user.getPassword())
                    ? user.getPassword()
                    : BCrypt.hashpw(user.getPassword(), BCrypt.gensalt(12)));
        }
        boolean roleChanged = !Objects.equals(previousRole, existing.getRole());
        if (passwordChanged || roleChanged) {
            existing.setSecurityVersion(nextSecurityVersion(existing));
        }
        return userRepository.update(existing) > 0;
    }

    private boolean isBcrypt(String pwd) {
        // BCrypt 哈希以 $2a$、$2b$ 或 $2y$ 开头
        return pwd != null && (pwd.startsWith("$2a$")
                || pwd.startsWith("$2b$")
                || pwd.startsWith("$2y$"));
    }

    private void normalizeRole(User user) {
        String role = user.getRole() == null || user.getRole().isBlank()
                ? "VIEWER"
                : user.getRole().trim().toUpperCase();
        try {
            ControlPlaneRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }
        user.setRole(role);
    }

    public boolean setUserActive(Long userId, boolean isActive) {
        return userRepository.setActive(userId, isActive) > 0;
    }

    public boolean invalidateSessions(Long userId) {
        return userRepository.incrementSecurityVersion(userId) > 0;
    }

    public boolean deleteUser(Long id) {
        return userRepository.delete(id) > 0;
    }

    public long getUserCount() {
        return userRepository.countAll();
    }

    private long nextSecurityVersion(User user) {
        return user.getSecurityVersion() == null ? 1L : user.getSecurityVersion() + 1L;
    }
}
