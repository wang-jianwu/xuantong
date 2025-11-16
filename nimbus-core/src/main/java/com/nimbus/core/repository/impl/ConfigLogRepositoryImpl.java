package com.nimbus.core.repository.impl;

import com.easy.query.api.proxy.base.MapProxy;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import com.nimbus.core.model.ConfigItem;
import com.nimbus.core.model.ConfigLog;
import com.nimbus.core.repository.ConfigLogRepository;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConfigLogRepositoryImpl implements ConfigLogRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public long save(ConfigLog log) {
        return easyQuery.insertable(log).executeRows();
    }

    @Override
    public List<ConfigLog> findByConfigId(Long configId) {
        return easyQuery.queryable(ConfigLog.class)
                .where(o -> o.configId().eq(configId))
                .orderBy(c -> c.operateTime().desc())
                .toList();
    }

    @Override
    public List<ConfigLog> findByOperator(String operator) {
        return easyQuery.queryable(ConfigLog.class)
                .where(o -> o.operator().eq(operator))
                .orderBy(c -> c.operateTime().desc())
                .toList();
    }

    @Override
    public List<ConfigLog> findByTimeRange(Date startTime, Date endTime) {
        return easyQuery.queryable(ConfigLog.class)
                .where(o -> {
                    o.operateTime().ge(startTime);
                    o.operateTime().le(endTime);
                })
                .orderBy(c -> c.operateTime().desc())
                .toList();
    }

    @Override
    public Map<String, String> findChangesSince(String project, String environment, Date since) {
        List<Map<String, Object>> list = easyQuery.queryable(ConfigLog.class)
                .innerJoin(ConfigItem.class, (log, config)
                        -> config.id().eq(log.configId()))
                .where((log, config) -> {
                    config.project().eq(project);
                    config.environment().eq(environment);
                    log.operateTime().ge(since);
                })
                .select((c1, c2) ->
                        new MapProxy()
                                .put("key", c2.key())
                                .put("value", c2.value())
                )
                .toList();
        Map<String, String> result = new HashMap<>();
        list.forEach(map ->{
            String key = (String) map.get("key");
            String value = (String) map.get("value");
            result.put(key, value);
        });
        return result;
    }
}