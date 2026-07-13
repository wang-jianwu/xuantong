package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按 Broker 保存服务实例视图，并向上层暴露所有可达 Broker 的实例并集。
 */
class MultiBrokerServiceState {
    private final Map<String, BrokerView> brokerViews = new HashMap<String, BrokerView>();
    private Map<String, ServiceInstance> merged = new HashMap<String, ServiceInstance>();
    private long logicalRevision;

    synchronized Update replaceBroker(
            String brokerId, long brokerRevision, List<ServiceInstance> instances) {
        BrokerView current = brokerViews.get(brokerId);
        if (current != null && brokerRevision < current.revision) {
            return null;
        }
        Map<String, ServiceInstance> replacement = new HashMap<String, ServiceInstance>();
        if (instances != null) {
            for (ServiceInstance instance : instances) {
                if (isAvailable(instance) && instance.getInstanceId() != null) {
                    replacement.put(instance.getInstanceId(), copy(instance));
                }
            }
        }
        brokerViews.put(brokerId, new BrokerView(brokerRevision, replacement));
        return recompute("BROKER_SNAPSHOT", null);
    }

    synchronized Update applyEvent(
            String brokerId,
            String eventType,
            long brokerRevision,
            ServiceInstance instance) {
        BrokerView view = brokerViews.computeIfAbsent(brokerId, k -> new BrokerView(0L, new HashMap<>()));
        if (brokerRevision <= view.revision) return null;
        view.revision = brokerRevision;
        if (instance == null || instance.getInstanceId() == null) return null;

        if ("INSTANCE_DEREGISTERED".equals(eventType)
                || "INSTANCE_EXPIRED".equals(eventType)
                || !isAvailable(instance)) {
            view.instances.remove(instance.getInstanceId());
        } else {
            view.instances.put(instance.getInstanceId(), copy(instance));
        }
        return recompute(eventType, instance);
    }

    synchronized Update removeBroker(String brokerId) {
        if (brokerViews.remove(brokerId) == null) return null;
        return recompute("BROKER_DISCONNECTED", null);
    }

    synchronized ServiceSnapshot snapshot() {
        return snapshotOf(merged, logicalRevision);
    }

    private Update recompute(String eventType, ServiceInstance eventInstance) {
        Map<String, ServiceInstance> next = new HashMap<String, ServiceInstance>();
        for (BrokerView view : brokerViews.values()) {
            for (ServiceInstance candidate : view.instances.values()) {
                ServiceInstance current = next.get(candidate.getInstanceId());
                if (current == null || isNewerLease(candidate, current)) {
                    next.put(candidate.getInstanceId(), copy(candidate));
                }
            }
        }
        if (logicalEquals(merged, next)) return null;
        merged = next;
        logicalRevision++;
        return new Update(eventType, copy(eventInstance), snapshotOf(merged, logicalRevision));
    }

    private boolean isNewerLease(ServiceInstance candidate, ServiceInstance current) {
        long candidateLease = value(candidate.getLeaseStartedAt());
        long currentLease = value(current.getLeaseStartedAt());
        if (candidateLease != currentLease) return candidateLease > currentLease;
        int leaseIdComparison = text(candidate.getLeaseId()).compareTo(text(current.getLeaseId()));
        if (leaseIdComparison != 0) return leaseIdComparison > 0;
        return value(candidate.getLastHeartbeatAt()) > value(current.getLastHeartbeatAt());
    }

    private boolean logicalEquals(
            Map<String, ServiceInstance> left, Map<String, ServiceInstance> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (String instanceId : left.keySet()) {
            if (!sameLogicalInstance(left.get(instanceId), right.get(instanceId))) return false;
        }
        return true;
    }

    private boolean sameLogicalInstance(ServiceInstance left, ServiceInstance right) {
        return Objects.equals(left.getInstanceId(), right.getInstanceId())
                && Objects.equals(left.getLeaseId(), right.getLeaseId())
                && Objects.equals(left.getLeaseStartedAt(), right.getLeaseStartedAt())
                && Objects.equals(left.getIp(), right.getIp())
                && Objects.equals(left.getPort(), right.getPort())
                && Objects.equals(left.getWeight(), right.getWeight())
                && Objects.equals(left.getHealthy(), right.getHealthy())
                && Objects.equals(left.getEnabled(), right.getEnabled())
                && Objects.equals(left.getMetadata(), right.getMetadata());
    }

    private ServiceSnapshot snapshotOf(Map<String, ServiceInstance> source, long revision) {
        List<ServiceInstance> instances = new ArrayList<>();
        for (ServiceInstance instance : source.values()) instances.add(copy(instance));
        Collections.sort(instances, (left, right) -> left.getInstanceId().compareTo(right.getInstanceId()));
        return new ServiceSnapshot(revision, Collections.unmodifiableList(instances));
    }

    private boolean isAvailable(ServiceInstance instance) {
        return instance != null
                && Boolean.TRUE.equals(instance.getHealthy())
                && Boolean.TRUE.equals(instance.getEnabled());
    }

    private long value(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private ServiceInstance copy(ServiceInstance source) {
        if (source == null) return null;
        ServiceInstance target = new ServiceInstance();
        target.setNamespaceId(source.getNamespaceId());
        target.setGroupName(source.getGroupName());
        target.setServiceName(source.getServiceName());
        target.setInstanceId(source.getInstanceId());
        target.setLeaseId(source.getLeaseId());
        target.setLeaseStartedAt(source.getLeaseStartedAt());
        target.setIp(source.getIp());
        target.setPort(source.getPort());
        target.setWeight(source.getWeight());
        target.setHealthy(source.getHealthy());
        target.setEnabled(source.getEnabled());
        target.setMetadata(source.getMetadata());
        target.setOwnerNodeId(source.getOwnerNodeId());
        target.setRegisteredAt(source.getRegisteredAt());
        target.setLastHeartbeatAt(source.getLastHeartbeatAt());
        return target;
    }

    static class Update {
        private final String eventType;
        private final ServiceInstance instance;
        private final ServiceSnapshot snapshot;

        Update(String eventType, ServiceInstance instance, ServiceSnapshot snapshot) {
            this.eventType = eventType;
            this.instance = instance;
            this.snapshot = snapshot;
        }

        String eventType() { return eventType; }
        ServiceInstance instance() { return instance; }
        ServiceSnapshot snapshot() { return snapshot; }
    }

    private static class BrokerView {
        private long revision;
        private final Map<String, ServiceInstance> instances;

        private BrokerView(long revision, Map<String, ServiceInstance> instances) {
            this.revision = revision;
            this.instances = instances;
        }
    }
}
