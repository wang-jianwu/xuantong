package cloud.xuantong.core.repository.impl;

import cn.hutool.core.date.DateUtil;
import com.easy.query.api.proxy.base.MapProxy;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import cloud.xuantong.core.model.ChangeVo;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.model.ConfigLog;
import cloud.xuantong.core.model.proxy.ChangeVoProxy;
import cloud.xuantong.core.repository.ConfigLogRepository;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static cloud.xuantong.core.repository.impl.ConfigRepositoryImpl.getStringMap;

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
                    log.project().eq(project);
                    log.environment().eq(environment);
                    log.operateTime().ge(since);
                })
                .orderBy(c -> c.operateTime().desc())
                .select((c1, c2) ->
                        new MapProxy()
                                .put("key", c2.key())
                                .put("value", c2.value())
                )
                .toList();
        return getStringMap(list);
    }

    @Override
    public ConfigLog findById(Long logId) {
        return easyQuery.queryable(ConfigLog.class).whereById(logId).firstOrNull();
    }

    @Override
    public List<ChangeVo> findLastChanges(int limit) {
        return easyQuery.queryable(ConfigLog.class)
                .innerJoin(ConfigItem.class, (log, config) -> config.id().eq(log.configId()))
                .where((log, config) -> {
                    log.operateTime().ge(DateUtil.offsetDay(new Date(), -7));
                })
                .select((c1, c2) ->  new ChangeVoProxy()
                        .operator().set(c1.operator())
                        .operateTime().set(c1.operateTime())
                        .project().set(c1.project())
                        .environment().set(c1.environment())
                        .key().set(c2.key())
                )
                .orderBy(c -> c.operateTime().desc())
                .limit(limit)
                .toList();
    }
}