package cloud.xuantong.core.repository;

import cloud.xuantong.core.model.Project;

import java.util.List;

/**
 * 项目数据访问接口
 */
public interface ProjectRepository {
    List<Project> findAll();
    Project findByCode(String code);
    long save(Project project);
    long update(Project project);
    long setActive(String code, boolean isActive);
    long countAll();
}