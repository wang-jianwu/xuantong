package cloud.xuantong.core.repository;

import cloud.xuantong.core.model.Environment;

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