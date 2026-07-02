package cloud.xuantong.core.repository.impl;

import cn.hutool.core.util.StrUtil;
import com.easy.query.api.proxy.base.MapProxy;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.api.pagination.EasyPageResult;
import com.easy.query.solon.annotation.Db;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.repository.ConfigRepository;
import org.jetbrains.annotations.NotNull;
import org.noear.solon.annotation.Component;

import java.util.*;

@Component
public class ConfigRepositoryImpl implements ConfigRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public ConfigItem findByKey(String key, String environment, String project) {
        return easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.key().eq(key);
                    o.and(() -> {
                        o.environment().eq(environment);
                        o.project().eq(project);
                    });
                }).firstOrNull();
    }

    @Override
    public EasyPageResult<ConfigItem> findByProject(String project, String environment, String keyWords, Long pn, Long size) {
        return easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.project().eq(project);
                    o.environment().eq(environment);
                    o.and(() -> {
                        o.or(() -> {
                            o.key().like(StrUtil.isNotBlank(keyWords), keyWords);
                            o.description().like(StrUtil.isNotBlank(keyWords), keyWords);
                        });
                    });
                })
                .orderBy(o -> o.key().asc())
                .toPageResult(pn, size);
    }

    @Override
    public long save(ConfigItem config) {
        return easyQuery.insertable(config).executeRows(true);
    }

    @Override
    public long update(ConfigItem config) {
        return easyQuery.updatable(ConfigItem.class)
                .setColumns(cp -> cp.value().set(config.getValue()))
                .setColumns(cp -> cp.description().set(config.getDescription()))
                .setColumns(cp -> cp.valueType().set(config.getValueType()))
                .setColumns(cp -> cp.isEncrypted().set(config.getIsEncrypted()))
                .setColumns(cp -> cp.version().set(config.getVersion() + 1))
                .setColumns(cp -> cp.updatedAt().set(new Date()))
                .where(o -> o.id().eq(config.getId()))
                .executeRows();
    }

    @Override
    public long delete(Long id) {
        return easyQuery.deletable(ConfigItem.class)
                .allowDeleteStatement(true)
                .where(o -> o.id().eq(id))
                .executeRows();
    }

    @Override
    public List<ConfigItem> findHistory(Long configId) {

        // 首先获取当前的配置项信息
        ConfigItem currentConfig = findById(configId);
        if (currentConfig == null) {
            return new ArrayList<>();
        }

        // 查询所有相同 key、project、environment 的配置项历史
        return easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.key().eq(currentConfig.getKey());
                    o.project().eq(currentConfig.getProject());
                    o.environment().eq(currentConfig.getEnvironment());
                })
                .orderBy(c -> c.updatedAt().desc())
                .toList();
    }

    @Override
    public ConfigItem findById(Long id) {
        return easyQuery.queryable(ConfigItem.class)
                .where(o -> o.id().eq(id))
                .firstOrNull();
    }

    @Override
    public long countAll() {
        return easyQuery.queryable(ConfigItem.class)
                .count();
    }

    @Override
    public ConfigItem findByVersion(Long configId, Integer version) {
        return easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.id().eq(configId);
                    o.version().eq(version);
                })
                .firstOrNull();
    }

    @Override
    public long countTodayChanges() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        Date todayStart = calendar.getTime();

        calendar.add(Calendar.DAY_OF_MONTH, 1);
        Date tomorrowStart = calendar.getTime();

        return easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.updatedAt().ge(todayStart);
                    o.updatedAt().lt(tomorrowStart);
                })
                .count();
    }


    @Override
    public Map<String, String> findByProjectAndEnvironment(String project, String env) {
        List<Map<String, Object>> list = easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.project().eq(project);
                    o.environment().eq(env);
                })
                .select(c -> new MapProxy()
                        .put("key", c.key())
                        .put("value", c.value())
                )
                .toList();

        return getStringMap(list);
    }

    @Override
    public Map<String, String> findByProjectsAndEnvironment(List<String> projects, String env) {
        if (projects == null || projects.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        List<java.util.Map<String, Object>> list = easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.project().in(projects);
                    o.environment().eq(env);
                })
                .select(c -> new MapProxy()
                        .put("key", c.key())
                        .put("value", c.value())
                        .put("project", c.project())
                )
                .toList();
        return getStringMap(list);
    }

    @Override
    public Map<String, String> findByKeysAndEnvironment(java.util.Set<String> keys, String env) {
        if (keys == null || keys.isEmpty() || env == null || env.trim().isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        // 使用 IN 查询批量获取指定keys的配置
        List<Map<String, Object>> list = easyQuery.queryable(ConfigItem.class)
                .where(o -> {
                    o.key().in(keys);
                    o.environment().eq(env);
                })
                .select(c -> new MapProxy()
                        .put("key", c.key())
                        .put("value", c.value())
                )
                .toList();

        return getStringMap(list);
    }

    @NotNull
    static Map<String, String> getStringMap(List<Map<String, Object>> list) {
        Map<String, String> result = new HashMap<>();
        list.forEach(map -> {
            String key = (String) map.get("key");
            String value = (String) map.get("value");
            // DB value=NULL 用空串表示"已配置但值为空"，
            // 避免下游 ConcurrentHashMap 不支持 null value 导致 NPE
            result.put(key, value != null ? value : "");
        });

        return result;
    }
}