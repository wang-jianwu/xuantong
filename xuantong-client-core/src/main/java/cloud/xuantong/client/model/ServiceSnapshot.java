package cloud.xuantong.client.model;

import java.util.List;

public class ServiceSnapshot {
    private final long revision;
    private final long compactionRevision;
    private final long serverTimeEpochMs;
    private final List<ServiceInstance> instances;

    public ServiceSnapshot(long revision, List<ServiceInstance> instances) {
        this(revision, 0L, 0L, instances);
    }

    public ServiceSnapshot(
            long revision,
            long compactionRevision,
            long serverTimeEpochMs,
            List<ServiceInstance> instances) {
        this.revision = revision;
        this.compactionRevision = compactionRevision;
        this.serverTimeEpochMs = serverTimeEpochMs;
        this.instances = List.copyOf(instances == null ? List.of() : instances);
    }

    public long getRevision() { return revision; }
    public long getCompactionRevision() { return compactionRevision; }
    public long getServerTimeEpochMs() { return serverTimeEpochMs; }
    public List<ServiceInstance> getInstances() { return instances; }
}
