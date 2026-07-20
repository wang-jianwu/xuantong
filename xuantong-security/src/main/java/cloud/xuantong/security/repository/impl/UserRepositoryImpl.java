package cloud.xuantong.security.repository.impl;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.repository.UserRepository;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;

@Component
public class UserRepositoryImpl implements UserRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public User findByUsername(String username) {
        return easyQuery.queryable(User.class)
                .where(o -> o.username().eq(username))
                .firstOrNull();
    }

    @Override
    public User findByEmail(String email) {
        return easyQuery.queryable(User.class)
                .where(o -> o.email().eq(email))
                .firstOrNull();
    }

    @Override
    public List<User> findAll() {
        return easyQuery.queryable(User.class)
                .orderBy(o -> o.username().asc())
                .toList();
    }

    @Override
    public PageResult<User> findPage(
            String keyword, String role, Boolean active, PageQuery pageQuery) {
        String normalizedKeyword = normalize(keyword);
        String normalizedRole = normalize(role);
        var result = easyQuery.queryable(User.class)
                .where(o -> {
                    o.role().eq(normalizedRole != null, normalizedRole);
                    o.isActive().eq(active != null, active);
                    if (normalizedKeyword != null) {
                        o.or(() -> {
                            o.username().contains(normalizedKeyword);
                            o.email().contains(normalizedKeyword);
                            o.realName().contains(normalizedKeyword);
                        });
                    }
                })
                .orderBy(o -> o.username().asc())
                .toPageResult(pageQuery.page(), pageQuery.pageSize());
        return PageResult.of(pageQuery, result.getTotal(), result.getData());
    }

    @Override
    public long save(User user) {
        user.setCreatedAt(new Date());
        return easyQuery.insertable(user).executeRows();
    }

    @Override
    public long update(User user) {
        return easyQuery.updatable(user)
                .executeRows();
    }

    @Override
    public long updateLoginTime(Long userId) {
        return easyQuery.updatable(User.class)
                .setColumns(u -> u.lastLoginTime().set(new Date()))
                .where(o -> o.id().eq(userId))
                .executeRows();
    }

    @Override
    public long setActive(Long userId, boolean isActive) {
        User user = findById(userId);
        if (user == null) {
            return 0;
        }
        user.setIsActive(isActive);
        user.setSecurityVersion(nextSecurityVersion(user));
        return update(user);
    }

    @Override
    public long incrementSecurityVersion(Long userId) {
        User user = findById(userId);
        if (user == null) {
            return 0;
        }
        user.setSecurityVersion(nextSecurityVersion(user));
        return update(user);
    }

    @Override
    public User findById(Long id) {
        return easyQuery.queryable(User.class).whereById(id).firstOrNull();
    }

    @Override
    public long delete(Long id) {
        return easyQuery.deletable(User.class)
                .allowDeleteStatement(true)
                .where(o -> o.id().eq(id)).executeRows();
    }

    @Override
    public long countAll() {
        return easyQuery.queryable(User.class).count();
    }

    private long nextSecurityVersion(User user) {
        return user.getSecurityVersion() == null ? 1L : user.getSecurityVersion() + 1L;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
