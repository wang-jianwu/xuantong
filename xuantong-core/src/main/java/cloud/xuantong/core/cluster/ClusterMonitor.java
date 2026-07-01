package cloud.xuantong.core.cluster;

/**
 * 集群监控接口
 * <p>
 * 提供集群连接状态查询，解耦 Controller 与 ClusterSyncPlayer 实现。
 */
public interface ClusterMonitor {

    /**
     * 获取活跃的集群连接数
     */
    int getActiveConnectionCount();
}
