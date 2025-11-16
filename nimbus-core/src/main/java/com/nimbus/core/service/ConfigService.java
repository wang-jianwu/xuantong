package com.nimbus.core.service;

import com.easy.query.core.api.pagination.EasyPageResult;
import com.nimbus.core.listener.model.ConfigChangeEvent;
import com.nimbus.core.model.ConfigItem;
import com.nimbus.core.model.ConfigLog;
import com.nimbus.core.model.User;
import com.nimbus.core.repository.ConfigLogRepository;
import com.nimbus.core.repository.ConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventBus;
import org.noear.solon.core.handle.Context;
import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.cache.CacheService;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ConfigService {
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
        // 通知监听器
        EventBus.publishAsync(new ConfigChangeEvent(config.getKey(), config.getValue(), config.getProject(), config.getEnvironment()));
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

    public boolean revertToVersion(Long configId, Integer version) {
        ConfigItem targetVersion = configRepository.findByVersion(configId, version);
        if (targetVersion == null) {
            return false;
        }

        // 获取当前版本号
        ConfigItem current = configRepository.findById(configId);
        if (current == null) {
            return false;
        }

        // 更新配置项为目标版本
        ConfigItem revertedConfig = new ConfigItem();
        revertedConfig.setId(configId);
        revertedConfig.setKey(targetVersion.getKey());
        revertedConfig.setValue(targetVersion.getValue());
        revertedConfig.setDescription(targetVersion.getDescription());
        revertedConfig.setEnvironment(targetVersion.getEnvironment());
        revertedConfig.setProject(targetVersion.getProject());
        revertedConfig.setVersion(current.getVersion() + 1);
        revertedConfig.setUpdatedAt(new Date());

        boolean result = configRepository.update(revertedConfig) > 0;
        if (result) {
            // 记录REVERT日志
            ConfigLog log = new ConfigLog();
            log.setConfigId(configId);
            log.setOperation("REVERT");
            log.setOldValue(current.getValue());
            log.setNewValue(targetVersion.getValue());
            log.setOperator(getCurrentUser());
            log.setOperateTime(new Date());
            log.setIpAddress(Context.current().realIp());
            configLogRepository.save(log);

            // 清除缓存
            String cacheKey = buildCacheKey(revertedConfig.getKey(),
                    revertedConfig.getEnvironment(),
                    revertedConfig.getProject());
            cacheService.remove(cacheKey);

            // 通知监听器
            EventBus.publishAsync(new ConfigChangeEvent(revertedConfig.getKey(), revertedConfig.getValue(), revertedConfig.getProject(), revertedConfig.getEnvironment()));
        }
        return result;
    }

    public Map<String, String> findByProjectAndEnvironment(String project, String environment) {
        return configRepository.findByProjectAndEnvironment(project, environment);
    }

    public Map<String, String> findChangesSince(String app, String env, Date date) {
        return configLogRepository.findChangesSince(app, env, date);
    }
}