package cloud.xuantong.client.model;

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

    public String getNamespaceId() { return namespaceId; }
    public void setNamespaceId(String namespaceId) { this.namespaceId = namespaceId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public Long getServiceGeneration() { return serviceGeneration; }
    public void setServiceGeneration(Long serviceGeneration) {
        this.serviceGeneration = serviceGeneration;
    }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }
    public Long getLeaseStartedAt() { return leaseStartedAt; }
    public void setLeaseStartedAt(Long leaseStartedAt) { this.leaseStartedAt = leaseStartedAt; }
    public Long getLeaseEpoch() { return leaseEpoch; }
    public void setLeaseEpoch(Long leaseEpoch) { this.leaseEpoch = leaseEpoch; }
    public Long getRecoveryEpoch() { return recoveryEpoch; }
    public void setRecoveryEpoch(Long recoveryEpoch) { this.recoveryEpoch = recoveryEpoch; }
    public Long getRenewSequence() { return renewSequence; }
    public void setRenewSequence(Long renewSequence) { this.renewSequence = renewSequence; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }
    public Boolean getHealthy() { return healthy; }
    public void setHealthy(Boolean healthy) { this.healthy = healthy; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getOwnerNodeId() { return ownerNodeId; }
    public void setOwnerNodeId(String ownerNodeId) { this.ownerNodeId = ownerNodeId; }
    public Long getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Long registeredAt) { this.registeredAt = registeredAt; }
    public Long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Long lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
}
