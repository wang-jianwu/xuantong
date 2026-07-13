package cloud.xuantong.client.model;

import java.util.List;

public class ServiceChangeEvent {
    private final String namespace;
    private final String group;
    private final String serviceName;
    private final String eventType;
    private final long revision;
    private final ServiceInstance instance;
    private final List<ServiceInstance> availableInstances;

    public ServiceChangeEvent(
            String namespace,
            String group,
            String serviceName,
            String eventType,
            long revision,
            ServiceInstance instance,
            List<ServiceInstance> availableInstances) {
        this.namespace = namespace;
        this.group = group;
        this.serviceName = serviceName;
        this.eventType = eventType;
        this.revision = revision;
        this.instance = instance;
        this.availableInstances = availableInstances;
    }

    public String getNamespace() { return namespace; }
    public String getGroup() { return group; }
    public String getServiceName() { return serviceName; }
    public String getEventType() { return eventType; }
    public long getRevision() { return revision; }
    public ServiceInstance getInstance() { return instance; }
    public List<ServiceInstance> getAvailableInstances() { return availableInstances; }
}
