package com.xuantong.core.cluster;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 集群配置
 */
@Configuration
public class ClusterConfig {

    @Inject("${config.center.cluster.nodes}")
    private List<String> clusterNodes;

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