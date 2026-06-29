package cloud.xuantong.core.service;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.repository.UserRepository;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.List;

@Component
public class UserService {
    @Inject
    private UserRepository userRepository;

    public User authenticate(String username, String password) {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            return null;
        }

        // 兼容旧 MD5 密码，首次登录成功后自动升级为 BCrypt
        if (user.getPassword().length() == 32) {
            // MD5 格式
            if (SecureUtil.md5(password).equals(user.getPassword())) {
                updatePasswordToBcrypt(user, password);
                userRepository.updateLoginTime(user.getId());
                return user;
            }
        } else {
            // BCrypt 格式
            if (BCrypt.checkpw(password, user.getPassword())) {
                userRepository.updateLoginTime(user.getId());
                return user;
            }
        }
        return null;
    }

    private void updatePasswordToBcrypt(User user, String rawPassword) {
        user.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        userRepository.update(user);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(Long id) {
        return userRepository.findById(id);
    }

    public boolean createUser(User user) {
        if (userRepository.findByUsername(user.getUsername()) != null) {
            throw new RuntimeException("用户名已存在");
        }
        if (userRepository.findByEmail(user.getEmail()) != null) {
            throw new RuntimeException("邮箱已存在");
        }

        user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt()));
        return userRepository.save(user) > 0;
    }

    public boolean updateUser(User user) {
        return userRepository.update(user) > 0;
    }

    public boolean setUserActive(Long userId, boolean isActive) {
        return userRepository.setActive(userId, isActive) > 0;
    }

    public boolean deleteUser(Long id) {
        return userRepository.delete(id) > 0;
    }

    public long getUserCount() {
        return userRepository.countAll();
    }
}