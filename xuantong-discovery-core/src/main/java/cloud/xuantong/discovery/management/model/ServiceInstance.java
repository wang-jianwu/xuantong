package cloud.xuantong.discovery.management.model;

import lombok.Data;

@Data
public class ServiceInstance {
    private String namespaceId;
    private String groupName;
    private String serviceName;
    private String instanceId;
    private Long serviceGeneration;
    private String leaseId;
    private Long leaseStartedAt;
    private Long leaseEpoch;
    private Long recoveryEpoch;
    private Long renewSequence;
    private Long expiresAt;
    private String ip;
    private Integer port;
    private Double weight;
    private Boolean healthy;
    private Boolean enabled;
    private String metadata;
    private String ownerNodeId;
    private Long registeredAt;
    private Long lastHeartbeatAt;
}
