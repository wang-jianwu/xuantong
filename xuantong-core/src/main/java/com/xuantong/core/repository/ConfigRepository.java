package com.xuantong.core.repository;

import com.easy.query.core.api.pagination.EasyPageResult;
import com.xuantong.core.model.ConfigItem;

import java.util.List;
import java.util.Map;

/**
 * 配置数据访问接口
 */
public interface ConfigRepository {
    ConfigItem findByKey(String key, String environment, String project);
    EasyPageResult<ConfigItem> findByProject(String project, String environment, String keyWords, Long pn, Long size);
    long save(ConfigItem config);
    long update(ConfigItem config);
    long delete(Long id);
    List<ConfigItem> findHistory(Long configId);

    ConfigItem findById(Long id);
    ConfigItem findByVersion(Long configId, Integer version);
    long countAll();
    long countTodayChanges();

    Map<String, String> findByProjectAndEnvironment(String project, String env);

    /**
     * 批量查询多个项目的配置
     */
    Map<String, String> findByProjectsAndEnvironment(List<String> projects, String env);
}