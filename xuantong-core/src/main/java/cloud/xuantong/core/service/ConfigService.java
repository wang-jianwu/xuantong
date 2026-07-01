package cloud.xuantong.core.service;

import com.easy.query.core.api.pagination.EasyPageResult;
import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import cloud.xuantong.core.model.ChangeVo;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.model.ConfigLog;
import cloud.xuantong.core.model.User;
import cloud.xuantong.core.repository.ConfigLogRepository;
import cloud.xuantong.core.repository.ConfigRepository;
import cloud.xuantong.core.util.AesEncryptor;
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

/**
 * 配置服务
 * <p>
 * 职责：配置 CRUD + 加密 + 缓存 + 变更日志
 * <p>
 * 注意：推送由 Controller 通过 EventBus 触发，Service 不直接推送
 */
@Component
public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    @Inject
    private ConfigRepository configRepository;

    @Inject
    private ConfigLogRepository configLogRepository;

    @Inject
    private CacheService cacheService;

    @Inject("${config.encryptKey:}")
    private String encryptKey;

    private volatile AesEncryptor encryptor;

    // ===== 加密器 =====

    private AesEncryptor getEncryptor() {
        if (encryptor == null) {
            synchronized (this) {
                if (encryptor == null) {
                    encryptor = new AesEncryptor(encryptKey);
                }
            }
        }
        return encryptor;
    }

    private void decryptMapValues(Map<String, String> map) {
        if (map == null || !getEncryptor().isEnabled()) return;
        map.replaceAll((k, v) -> getEncryptor().decrypt(v));
    }

    // ===== 缓存 =====

    private String buildCacheKey(String key, String environment, String project) {
        return String.format("config:%s:%s:%s", project, environment, key);
    }

    private void removeCache(String key, String environment, String project) {
        cacheService.remove(buildCacheKey(key, environment, project));
    }

    // ===== 上下文工具 =====

    private String getCurrentUser() {
        try {
            Context ctx = Context.current();
            if (ctx != null) {
                User user = ctx.session("user", User.class);
                if (user != null) {
                    return user.getRealName();
                }
            }
        } catch (Exception ignored) {}
        return "system";
    }

    private String getCurrentIp() {
        try {
            Context ctx = Context.current();
            if (ctx != null) {
                return ctx.realIp();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ===== 查询 =====

    public ConfigItem getConfig(String key, String environment, String project) {
        String cacheKey = buildCacheKey(key, environment, project);
        ConfigItem config = cacheService.get(cacheKey, ConfigItem.class);

        if (config == null) {
            config = configRepository.findByKey(key, environment, project);
            if (config != null) {
                cacheService.store(cacheKey, config, 30 * 60 * 60);
            }
        }
        if (config != null && Boolean.TRUE.equals(config.getIsEncrypted())) {
            config.setValue(getEncryptor().decrypt(config.getValue()));
        }
        return config;
    }

    public ConfigItem getConfigById(Long id) {
        return configRepository.findById(id);
    }

    public EasyPageResult<ConfigItem> getProjectConfigs(String project, String environment, String keyWords, Long pn, Long size) {
        return configRepository.findByProject(project, environment, keyWords, pn, size);
    }

    public Map<String, String> findByProjectAndEnvironment(String project, String environment) {
        Map<String, String> result = configRepository.findByProjectAndEnvironment(project, environment);
        decryptMapValues(result);
        return result;
    }

    public Map<String, String> findByProjectsAndEnvironment(List<String> projects, String environment) {
        if (projects == null || projects.isEmpty()) {
            return Collections.emptyMap();
        }
        if (projects.size() == 1) {
            return findByProjectAndEnvironment(projects.get(0), environment);
        }
        Map<String, String> result = configRepository.findByProjectsAndEnvironment(projects, environment);
        decryptMapValues(result);
        return result;
    }

    public Map<String, String> getBatchConfigsByKeys(java.util.Set<String> keys, String env) {
        if (keys == null || keys.isEmpty() || env == null || env.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> result = configRepository.findByKeysAndEnvironment(keys, env);
            decryptMapValues(result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to get batch configs for keys: {} in env: {}", keys, env, e);
            return Collections.emptyMap();
        }
    }

    public Map<String, String> findChangesSince(String app, String env, Date date) {
        return configLogRepository.findChangesSince(app, env, date);
    }

    // ===== 统计 =====

    public long getConfigCount() {
        return configRepository.countAll();
    }

    public long getTodayChangeCount() {
        return configRepository.countTodayChanges();
    }

    public List<ChangeVo> getRecentChanges(int limit) {
        return configLogRepository.findLastChanges(limit);
    }

    public List<ConfigLog> getConfigHistory(Long configId) {
        return configLogRepository.findByConfigId(configId);
    }

    // ===== 写入操作（事务保护，不推送） =====

    /**
     * 保存配置
     * @return 保存成功返回 ConfigChangeEvent（供 Controller 推送），失败返回 null
     */
    @Transaction
    public ConfigChangeEvent saveConfig(ConfigItem config) {
        ConfigItem existing = configRepository.findByKey(config.getKey(),
                config.getEnvironment(), config.getProject());

        boolean result;
        String oldValue = null;

        if (Boolean.TRUE.equals(config.getIsEncrypted())) {
            config.setValue(getEncryptor().encrypt(config.getValue()));
        }

        if (existing != null) {
            oldValue = existing.getValue();
            config.setId(existing.getId());
            config.setVersion(existing.getVersion());
            result = configRepository.update(config) > 0;
            if (result) {
                saveConfigLog(config.getId(), "UPDATE", oldValue, config.getValue(),
                        config.getProject(), config.getEnvironment());
            }
        } else {
            config.setVersion(1);
            result = configRepository.save(config) > 0;
            if (result) {
                saveConfigLog(config.getId(), "CREATE", null, config.getValue(),
                        config.getProject(), config.getEnvironment());
            }
        }

        if (result) {
            removeCache(config.getKey(), config.getEnvironment(), config.getProject());
            return new ConfigChangeEvent(config.getKey(), config.getValue(),
                    config.getProject(), config.getEnvironment());
        }
        return null;
    }

    /**
     * 删除配置
     * @return 删除成功返回 ConfigChangeEvent，失败返回 null
     */
    @Transaction
    public ConfigChangeEvent deleteConfig(Long id) {
        ConfigItem config = configRepository.findById(id);
        if (config == null) {
            return null;
        }
        boolean result = configRepository.delete(id) > 0;
        if (result) {
            saveConfigLog(id, "DELETE", config.getValue(), null,
                    config.getProject(), config.getEnvironment());
            removeCache(config.getKey(), config.getEnvironment(), config.getProject());
            return new ConfigChangeEvent(config.getKey(), null,
                    config.getProject(), config.getEnvironment());
        }
        return null;
    }

    /**
     * 回滚到指定版本
     * @return 回滚成功返回 ConfigChangeEvent，失败返回 null
     */
    @Transaction
    public ConfigChangeEvent revertToVersion(Long logId) {
        ConfigLog configLog = configLogRepository.findById(logId);
        if (configLog == null) {
            return null;
        }

        ConfigItem current = configRepository.findById(configLog.getConfigId());
        if (current == null) {
            return null;
        }

        String newValue = configLog.getNewValue();
        String oldValue = current.getValue();
        current.setValue(newValue);
        current.setVersion(current.getVersion() + 1);
        current.setUpdatedAt(new Date());

        boolean result = configRepository.update(current) > 0;
        if (result) {
            saveConfigLog(current.getId(), "REVERT", oldValue, newValue,
                    current.getProject(), current.getEnvironment());
            removeCache(current.getKey(), current.getEnvironment(), current.getProject());
            return new ConfigChangeEvent(current.getKey(), newValue,
                    current.getProject(), current.getEnvironment());
        }
        return null;
    }

    // ===== 内部方法 =====

    private void saveConfigLog(Long configId, String operation, String oldValue, String newValue,
                               String project, String environment) {
        ConfigLog log = new ConfigLog();
        log.setConfigId(configId);
        log.setOperation(operation);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setOperator(getCurrentUser());
        log.setOperateTime(new Date());
        log.setIpAddress(getCurrentIp());
        log.setProject(project);
        log.setEnvironment(environment);
        configLogRepository.save(log);
    }
}
