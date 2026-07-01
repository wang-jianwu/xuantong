package cloud.xuantong.core.listener;

/**
 * 配置推送抽象接口
 * <p>
 * 解耦 Broadcaster（编排）与 BrokerListener（传输实现）。
 * 未来可替换为 HTTP / MQ 等实现。
 */
public interface ConfigPusher {

    /**
     * 全量推送配置变更
     */
    void pushConfigChange(String project, String env, String changeJson);

    /**
     * 推送配置变更
     * @param gray true=灰度（单播1台），false=全量
     */
    void pushConfigChange(String project, String env, String changeJson, boolean gray);

    /**
     * 推送配置变更（支持 IP / 比例灰度）
     * @param gray       true=灰度
     * @param targetIp   指定目标 IP（不为 null 时按 IP 推）
     * @param percentage 按比例（0~1）
     */
    void pushConfigChange(String project, String env, String changeJson,
                          boolean gray, String targetIp, double percentage);

    /**
     * 集群同步广播
     */
    void broadcastClusterSync(String syncJson);
}
