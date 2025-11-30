package cloud.xuantong.core.cluster;

import lombok.Data;

/**
 * 集群消息
 */
@Data
public class ClusterMessage {
    private String messageId;
    private String type;
    private String sourceNodeId;
    private long timestamp;
    private Object payload;
}
