package cloud.xuantong.core.v2.config;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import java.util.UUID;

/**
 * 当前 Broker 的运行时标识，仅用于事件来源和实例诊断，不参与 Broker 间同步。
 */
@Configuration
public class BrokerNodeConfig {
    private final String generatedNodeId = UUID.randomUUID().toString().substring(0, 8);
    @Inject("${broker.nodeId:}")
    private String configuredNodeId;

    public String getNodeId() {
        return configuredNodeId == null || configuredNodeId.isBlank()
                ? generatedNodeId
                : configuredNodeId.trim();
    }
}
