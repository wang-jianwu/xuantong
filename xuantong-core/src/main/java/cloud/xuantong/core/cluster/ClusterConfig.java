package cloud.xuantong.core.cluster;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 集群配置
 */
@Configuration
public class ClusterConfig {

    @Inject("${config.center.cluster.nodes}")
    private List<String> clusterNodes;

    /** 当前节点唯一标识，集群同步防环用 */
    private final String nodeId = UUID.randomUUID().toString().substring(0, 8);

    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取集群节点地址列表
     */
    public List<String> getClusterNodeAddresses() {
        if (clusterNodes == null || clusterNodes.isEmpty()) {
            return new ArrayList<>();
        }

        // 清理空格并验证格式
        return clusterNodes.stream()
                .map(String::trim)
                .filter(addr -> addr.startsWith("sd:ws://") || addr.startsWith("sd:tcp://"))
                .collect(Collectors.toList());
    }

    /**
     * 是否启用集群模式
     */
    public boolean isClusterEnabled() {
        return !getClusterNodeAddresses().isEmpty();
    }
}