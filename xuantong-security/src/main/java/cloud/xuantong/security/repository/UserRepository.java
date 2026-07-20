package cloud.xuantong.security.repository;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;

import java.util.List;

/**
 * 用户数据访问接口
 */
public interface UserRepository {
    User findByUsername(String username);
    User findByEmail(String email);
    List<User> findAll();
    PageResult<User> findPage(
            String keyword, String role, Boolean active, PageQuery pageQuery);
    long save(User user);
    long update(User user);
    long updateLoginTime(Long userId);
    long setActive(Long userId, boolean isActive);
    long incrementSecurityVersion(Long userId);

    User findById(Long id);

    long delete(Long id);
    long countAll();
}
