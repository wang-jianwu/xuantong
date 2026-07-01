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

    /**
     * 保存配置（事务保护 DB 操作，推送在事务外）
     * @param pushMode none=不推送, gray=灰度(1台), all=全量
     */
    public boolean saveConfig(ConfigItem config, String pushMode) {
        ConfigItem existing = configRepository.findByKey(config.getKey(),
                config.getEnvironment(), config.getProject());

        boolean result = doSaveConfig(config, existing);

        // 推送（事务外）
        if (result && pushMode != null && !"none".equals(pushMode)) {
            ConfigChangeEvent event = new ConfigChangeEvent(
                    config.getKey(), config.getValue(),
                    config.getProject(), config.getEnvironment()
            );
            boolean gray = "gray".equals(pushMode);
            try {
                clusterBroadcaster.broadcastConfigChange(event, gray);
                logger.info("Config saved and {} pushed: {}={}", gray ? "gray" : "full", config.getKey(), config.getValue());
            } catch (Exception e) {
                logger.error("推送失败，但配置已保存", e);
            }
        } else {
            logger.info("Config saved (no push): {}={}", config.getKey(), config.getValue());
        }
        return result;
    }

    @Transaction
    private boolean doSaveConfig(ConfigItem config, ConfigItem existing) {
        boolean result;
        if (existing != null) {
            config.setId(existing.getId());
            config.setVersion(existing.getVersion());
            result = configRepository.update(config) > 0;

            if (result) {
                ConfigLog log = new ConfigLog();
                log.setConfigId(config.getId());
                log.setOperation("UPDATE");
                log.setOldValue(existing.getValue());
                log.setNewValue(config.getValue());
                log.setOperator(getCurrentUser());
                log.setOperateTime(new Date());
                log.setIpAddress(getCurrentIp());
                log.setProject(config.getProject());
                log.setEnvironment(config.getEnvironment());
                configLogRepository.save(log);
            }
        } else {
            config.setVersion(1);
            result = configRepository.save(config) > 0;

            if (result) {
                ConfigLog log = new ConfigLog();
                log.setConfigId(config.getId());
                log.setOperation("CREATE");
                log.setNewValue(config.getValue());
                log.setOperator(getCurrentUser());
                log.setOperateTime(new Date());
                log.setIpAddress(getCurrentIp());
                log.setProject(config.getProject());
                log.setEnvironment(config.getEnvironment());
                configLogRepository.save(log);
            }
        }

        if (result) {
            String cacheKey = buildCacheKey(config.getKey(), config.getEnvironment(), config.getProject());
            cacheService.remove(cacheKey);
        }
        return result;
    }

    private void broadcastConfigChange(ConfigChangeEvent event) {
        try {
            clusterBroadcaster.broadcastConfigChange(event);
        } catch (Exception e) {
            logger.error("集群广播失败，但配置已保存", e);
        }
    }

    private String getCurrentUser() {
        try {
            Context ctx = Context.current();
            if (ctx != null) {
                User user = ctx.session("user", User.class);
                if (user != null) {
                    return user.getRealName();
                }
            }
        } catch (Exception ignored) {
        }
        return "system";
    }

    private String getCurrentIp() {
        try {
            Context ctx = Context.current();
            if (ctx != null) {
                return ctx.realIp();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    @Transaction
    public boolean deleteConfig(Long id) {
        ConfigItem config = configRepository.findById(id);
        if (config == null) {
            return false;
        }
        boolean result = configRepository.delete(id) > 0;
        if (result) {
            // 记录 DELETE 操作日志
            ConfigLog log = new ConfigLog();
            log.setConfigId(id);
            log.setOperation("DELETE");
            log.setOldValue(config.getValue());
            log.setOperator(getCurrentUser());
            log.setOperateTime(new Date());
            log.setIpAddress(getCurrentIp());
            log.setProject(config.getProject());
            log.setEnvironment(config.getEnvironment());
            configLogRepository.save(log);

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

    /**
     * 回滚到指定版本（事务保护 DB 操作，广播在事务外）
     */
    public boolean revertToVersion(Long logId) {
        ConfigLog configLog = configLogRepository.findById(logId);
        if (configLog == null) {
            return false;
        }

        boolean result = doRevertToVersion(configLog);

        // 集群广播（事务外）
        if (result) {
            ConfigItem current = configRepository.findById(configLog.getConfigId());
            if (current != null) {
                ConfigChangeEvent event = new ConfigChangeEvent(
                        current.getKey(), configLog.getNewValue(),
                        current.getProject(), current.getEnvironment()
                );
                broadcastConfigChange(event);
            }
        }
        return result;
    }

    @Transaction
    private boolean doRevertToVersion(ConfigLog configLog) {
        ConfigItem current = configRepository.findById(configLog.getConfigId());
        if (current == null) {
            return false;
        }

        String newValue = configLog.getNewValue();
        String oldValue = current.getValue();
        current.setValue(newValue);
        current.setVersion(current.getVersion() + 1);
        current.setUpdatedAt(new Date());

        boolean result = configRepository.update(current) > 0;
        if (result) {
            ConfigLog log = new ConfigLog();
            log.setConfigId(current.getId());
            log.setOperation("REVERT");
            log.setOldValue(oldValue);
            log.setNewValue(newValue);
            log.setOperator(getCurrentUser());
            log.setOperateTime(new Date());
            log.setIpAddress(getCurrentIp());
            log.setProject(current.getProject());
            log.setEnvironment(current.getEnvironment());
            configLogRepository.save(log);

            String cacheKey = buildCacheKey(current.getKey(),
                    current.getEnvironment(),
                    current.getProject());
            cacheService.remove(cacheKey);
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