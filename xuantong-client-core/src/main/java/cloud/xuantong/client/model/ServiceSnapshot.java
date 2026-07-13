package cloud.xuantong.client.model;

import java.util.List;

public class ServiceSnapshot {
    private final long revision;
    private final List<ServiceInstance> instances;

    public ServiceSnapshot(long revision, List<ServiceInstance> instances) {
        this.revision = revision;
        this.instances = instances;
    }

    public long getRevision() { return revision; }
    public List<ServiceInstance> getInstances() { return instances; }
}
