package com.xuantong.core.repository;

import com.xuantong.core.model.User;

import java.util.List;

/**
 * 用户数据访问接口
 */
public interface UserRepository {
    User findByUsername(String username);
    User findByEmail(String email);
    List<User> findAll();
    long save(User user);
    long update(User user);
    long updateLoginTime(Long userId);
    long setActive(Long userId, boolean isActive);

    User findById(Long id);

    long delete(Long id);
    long countAll();
}