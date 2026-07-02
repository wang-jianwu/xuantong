package cloud.xuantong.core.listener.model;

import lombok.Data;

/**
 * 推送日志记录
 */
@Data
public class PushLog {
    private String project;
    private String env;
    private String changeKey;
    private int targetPlayerCount;
    private long timestamp;
}
