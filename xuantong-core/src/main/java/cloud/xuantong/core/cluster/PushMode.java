package cloud.xuantong.core.cluster;

/**
 * 推送模式
 */
public enum PushMode {
    /** 不推送 */
    NONE,
    /** 全量推送 */
    ALL,
    /** 灰度推送（随机1台） */
    GRAY,
    /** 指定 IP 灰度 */
    IP,
    /** 按比例灰度 */
    PERCENTAGE
}
