package com.xuantong.core.repository;

import com.xuantong.core.model.ChangeVo;
import com.xuantong.core.model.ConfigLog;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 配置日志数据访问接口
 */
public interface ConfigLogRepository {
    long save(ConfigLog log);
    List<ConfigLog> findByConfigId(Long configId);
    List<ConfigLog> findByOperator(String operator);
    List<ConfigLog> findByTimeRange(Date startTime, Date endTime);
    Map<String, String> findChangesSince(String project, String environment, Date since);

    ConfigLog findById(Long logId);

    List<ChangeVo> findLastChanges(int limit);
}