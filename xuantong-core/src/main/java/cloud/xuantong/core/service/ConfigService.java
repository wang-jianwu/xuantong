package cloud.xuantong.core.service;

import com.easy.query.core.api.pagination.EasyPageResult;
import cloud.xuantong.core.cluster.ConfigClusterBroadcaster;
import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import cloud.xuantong.core.model.ChangeVo;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.model.ConfigLog;
import cloud.xuantong.core.model.User;
import cloud.xuantong.core.repository.ConfigLogRepository;
import cloud.xuantong.core.repository.ConfigRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.cache.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);
    @Inject
    private ConfigRepository configRepository;

    @Inject
    private CacheService cacheService;

    private String buildCacheKey(String key, String environment, String project) {
        return String.format("config:%s:%s:%s", project, environment, key);
    }

    public ConfigItem getConfig(String key, String environment, String project) {
        String cacheKey = buildCacheKey(key, environment, project);
        ConfigItem config = cacheService.get(cacheKey, ConfigItem.class);

        if (config == null) {
            config = configRepository.findByKey(key, environment, project);
            if (config != null) {
                cacheService.store(cacheKey, config, 30 * 60 * 60);
            }
        }
        return config;
    }

    public EasyPageResult<ConfigItem> getProjectConfigs(String project, String environment, String keyWords, Long pn, Long size) {
        // 项目级配置不缓存，因变化频繁
        return configRepository.findByProject(project, environment, keyWords, pn, size);
    }

    @Inject
    private ConfigClusterBroadcaster clusterBroadcaster;

    @Transaction
    public boolean saveConfig(ConfigItem config) {
        ConfigItem existing = configRepository.findByKey(config.getKey(),
                config.getEnvironment(), config.getProject());

        boolean result;
        if (existing != null) {
            // 更新配置项
            config.setId(existing.getId());
            config.setVersion(existing.getVersion() + 1);
            result = configRepository.update(config) > 0;

            if (result) {
                // 记录UPDATE日志
                ConfigLog log = new ConfigLog();
                log.setConfigId(config.getId());
                log.setOperation("UPDATE");
                log.setOldValue(existing.getValue());
                log.setNewValue(config.getValue());
                log.setOperator(getCurrentUser());
                log.setOperateTime(new Date());
                log.setIpAddress(Context.current().realIp());
                configLogRepository.save(log);

                // 创建变更事件
                ConfigChangeEvent event = new ConfigChangeEvent(
                        config.getKey(), config.getValue(),
                        config.getProject(), config.getEnvironment()
                );
                // 集群广播
                try {
                    clusterBroadcaster.broadcastConfigChange(event);
                } catch (Exception e) {
                    logger.error("集群广播失败，但配置已保存", e);
                }
            }
        } else {
            // 创建新配置项
            config.setVersion(1);
            result = configRepository.save(config) > 0;

            if (result) {
                // 记录CREATE日志
                ConfigLog log = new ConfigLog();
                log.setConfigId(config.getId());
                log.setOperation("CREATE");
                log.setNewValue(config.getValue());
                log.setOperator(getCurrentUser());
                log.setOperateTime(new Date());
                log.setIpAddress(Context.current().realIp());
                configLogRepository.save(log);
            }
        }

        if (result) {
            String cacheKey = buildCacheKey(config.getKey(), config.getEnvironment(), config.getProject());
            cacheService.remove(cacheKey);
        }
        return result;
    }

    private String getCurrentUser() {
        // 从安全上下文中获取当前用户
        return Context.current().session("user", User.class).getRealName();
    }

    public boolean deleteConfig(Long id) {
        ConfigItem config = configRepository.findById(id);
        boolean result = configRepository.delete(id) > 0;
        if (result && config != null) {
            String cacheKey = buildCacheKey(config.getKey(), config.getEnvironment(), config.getProject());
            cacheService.remove(cacheKey);
        }
        return result;
    }

    public ConfigItem getConfigById(Long id) {
        return configRepository.findById(id);
    }

    public long getConfigCount() {
        return configRepository.countAll();
    }

    public long getTodayChangeCount() {
        return configRepository.countTodayChanges();
    }

    @Inject
    private ConfigLogRepository configLogRepository;

    public List<ConfigLog> getConfigHistory(Long configId) {
        return configLogRepository.findByConfigId(configId);
    }

    @Transaction
    public boolean revertToVersion(Long logId) {
        ConfigLog configLog = configLogRepository.findById(logId);
        if (configLog == null) {
            return false;
        }

        // 获取当前版本号
        ConfigItem current = configRepository.findById(configLog.getConfigId());
        if (current == null) {
            return false;
        }

        String newValue = configLog.getNewValue();
        String oldValue = current.getValue();
        // 更新配置项为目标版本
        current.setValue(newValue);
        current.setVersion(current.getVersion() + 1);
        current.setUpdatedAt(new Date());

        boolean result = configRepository.update(current) > 0;
        if (result) {
            // 记录REVERT日志
            ConfigLog log = new ConfigLog();
            log.setConfigId(current.getId());
            log.setOperation("REVERT");
            log.setOldValue(oldValue);
            log.setNewValue(newValue);
            log.setOperator(getCurrentUser());
            log.setOperateTime(new Date());
            log.setIpAddress(Context.current().realIp());
            configLogRepository.save(log);

            // 清除缓存
            String cacheKey = buildCacheKey(current.getKey(),
                    current.getEnvironment(),
                    current.getProject());
            cacheService.remove(cacheKey);

            // 创建变更事件
            ConfigChangeEvent event = new ConfigChangeEvent(
                    current.getKey(), newValue,
                    current.getProject(), current.getEnvironment()
            );
            // 集群广播
            try {
                clusterBroadcaster.broadcastConfigChange(event);
            } catch (Exception e) {
                logger.error("集群广播失败，但配置已回滚", e);
            }
        }
        return result;
    }

    public Map<String, String> findByProjectAndEnvironment(String project, String environment) {
        return configRepository.findByProjectAndEnvironment(project, environment);
    }

    /**
     * 批量查询多个项目的配置（支持多应用订阅）
     */
    public Map<String, String> findByProjectsAndEnvironment(List<String> projects, String environment) {
        if (projects == null || projects.isEmpty()) {
            return Collections.emptyMap();
        }

        if (projects.size() == 1) {
            return findByProjectAndEnvironment(projects.get(0), environment);
        }

        return configRepository.findByProjectsAndEnvironment(projects, environment);
    }

    public Map<String, String> findChangesSince(String app, String env, Date date) {
        return configLogRepository.findChangesSince(app, env, date);
    }

    public List<ChangeVo> getRecentChanges(int limit) {
        return configLogRepository.findLastChanges(limit);
    }

    /**
     * 批量查询指定配置键的值（按需加载优化）
     */
    public Map<String, String> getBatchConfigsByKeys(java.util.Set<String> keys, String env) {
        if (keys == null || keys.isEmpty() || env == null || env.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            // 批量查询
            return configRepository.findByKeysAndEnvironment(keys, env);
        } catch (Exception e) {
            logger.error("Failed to get batch configs for keys: {} in env: {}", keys, env, e);
            return Collections.emptyMap();
        }
    }
}