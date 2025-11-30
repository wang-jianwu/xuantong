package cloud.xuantong.core.service;

import com.easy.query.core.util.EasyMD5Util;
import cloud.xuantong.core.model.User;
import cloud.xuantong.core.repository.UserRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.List;

@Component
public class UserService {
    @Inject
    private UserRepository userRepository;

    public User authenticate(String username, String password) {
        User user = userRepository.findByUsername(username);
        if (user != null && EasyMD5Util.getMD5Hash(password).equals(user.getPassword())) {
            userRepository.updateLoginTime(user.getId());
            return user;
        }
        return null;
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

        user.setPassword(EasyMD5Util.getMD5Hash(user.getPassword()));
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