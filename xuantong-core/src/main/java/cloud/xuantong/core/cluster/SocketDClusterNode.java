package cloud.xuantong.core.cluster;

import lombok.Data;
import org.noear.socketd.transport.client.ClientSession;

/**
 * author 封于修
 * date 2025/11/23 11:37
 * 集群节点信息
 */
@Data
public class SocketDClusterNode {
    private String nodeId;
    private String address;
    private long lastHeartbeat;
    private boolean active;
    private ClientSession session;
}
