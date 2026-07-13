package cloud.xuantong.core.v2.event;

import lombok.Data;

import java.util.UUID;

@Data
public class ControlPlaneEvent {
    private String eventId;
    private String eventType;
    private String namespaceId;
    private String groupName;
    private String resourceName;
    private Long revision;
    private String sourceNodeId;
    private Long timestamp;
    private Object payload;

    public static ControlPlaneEvent create(
            String eventType,
            String namespaceId,
            String groupName,
            String resourceName,
            long revision,
            String sourceNodeId,
            Object payload) {
        ControlPlaneEvent event = new ControlPlaneEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setNamespaceId(namespaceId);
        event.setGroupName(groupName);
        event.setResourceName(resourceName);
        event.setRevision(revision);
        event.setSourceNodeId(sourceNodeId);
        event.setTimestamp(System.currentTimeMillis());
        event.setPayload(payload);
        return event;
    }
}
