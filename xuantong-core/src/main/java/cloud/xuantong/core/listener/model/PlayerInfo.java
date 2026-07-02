package cloud.xuantong.core.listener.model;

import lombok.Data;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃 Player（客户端）连接信息
 */
@Data
public class PlayerInfo {
    private String sessionId;
    /** @=name 中的 name */
    private String playerName;
    private String clientIp;
    private long connectedTime;
    /** 订阅的项目列表 */
    private Set<String> subscribedApps = ConcurrentHashMap.newKeySet();
    private long lastRequestTime;
}
