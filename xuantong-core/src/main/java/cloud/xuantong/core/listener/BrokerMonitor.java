package cloud.xuantong.core.listener;

import cloud.xuantong.core.listener.ConfigBrokerListener.PlayerInfo;
import cloud.xuantong.core.listener.ConfigBrokerListener.PushLog;

import java.util.List;

/**
 * Broker 监控接口
 * <p>
 * 提供客户端连接状态、推送日志等监控数据查询。
 * 解耦 Controller 与 BrokerListener 实现。
 */
public interface BrokerMonitor {

    /**
     * 获取所有活跃客户端
     */
    List<PlayerInfo> getActivePlayers();

    /**
     * 获取活跃客户端数量
     */
    int getActivePlayerCount();

    /**
     * 获取最近推送日志
     */
    List<PushLog> getPushLogs();

    /**
     * 检查是否有客户端订阅了指定项目
     */
    boolean hasSubscriber(String project, String env);
}
