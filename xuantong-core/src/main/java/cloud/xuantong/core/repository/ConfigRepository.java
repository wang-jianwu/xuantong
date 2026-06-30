package cloud.xuantong.core.repository;

import com.easy.query.core.api.pagination.EasyPageResult;
import cloud.xuantong.core.model.ConfigItem;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置数据访问接口
 */
public interface ConfigRepository {
    ConfigItem findByKey(String key, String environment, String project);
    EasyPageResult<ConfigItem> findByProject(String project, String environment, String keyWords, Long pn, Long size);
    long save(ConfigItem config);
    long update(ConfigItem config);
    long delete(Long id);
    /** @deprecated 该方法不可靠（当前实现只返回单行），请使用 ConfigLogRepository.findByConfigId 获取变更历史 */
    @Deprecated
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

    /**
     * 批量查询指定配置键的值（按需加载优化）
     */
    Map<String, String> findByKeysAndEnvironment(Set<String> keys, String env);
}