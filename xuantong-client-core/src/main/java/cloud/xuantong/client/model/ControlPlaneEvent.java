package cloud.xuantong.client.model;

import java.util.Map;

public class ControlPlaneEvent {
    private String eventId;
    private String eventType;
    private String namespaceId;
    private String groupName;
    private String resourceName;
    private Long revision;
    private Long timestamp;
    private Map<String, Object> payload;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getNamespaceId() { return namespaceId; }
    public void setNamespaceId(String namespaceId) { this.namespaceId = namespaceId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
