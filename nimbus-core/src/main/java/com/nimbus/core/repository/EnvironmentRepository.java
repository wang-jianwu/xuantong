package com.nimbus.core.repository;

import com.nimbus.core.model.Environment;
import java.util.List;

/**
 * 环境数据访问接口
 */
public interface EnvironmentRepository {
    List<Environment> findAll();
    Environment findByCode(String code);
    long save(Environment env);
    long setDefault(String code);
}