package com.nimbus.core.repository.impl;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import com.nimbus.core.model.User;
import com.nimbus.core.model.proxy.UserProxy;
import com.nimbus.core.repository.UserRepository;
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
        return easyQuery.updatable(User.class)
                .setColumns(u -> u.isActive().set(isActive))
                .where(o -> o.id().eq(userId))
                .executeRows();
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
}