package cloud.xuantong.client.discovery;

import cloud.xuantong.client.model.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class ServiceInstanceSelector {
    private final AtomicInteger roundRobinIndex = new AtomicInteger();

    public ServiceInstance select(List<ServiceInstance> instances, LoadBalanceStrategy strategy) {
        List<ServiceInstance> available = available(instances);
        if (available.isEmpty()) {
            return null;
        }
        LoadBalanceStrategy selectedStrategy = strategy == null
                ? LoadBalanceStrategy.ROUND_ROBIN : strategy;
        switch (selectedStrategy) {
            case RANDOM:
                return available.get(ThreadLocalRandom.current().nextInt(available.size()));
            case WEIGHTED_RANDOM:
                return weightedRandom(available);
            case ROUND_ROBIN:
            default:
                int index = Math.floorMod(roundRobinIndex.getAndIncrement(), available.size());
                return available.get(index);
        }
    }

    private List<ServiceInstance> available(List<ServiceInstance> instances) {
        List<ServiceInstance> available = new ArrayList<>();
        if (instances == null) {
            return available;
        }
        for (ServiceInstance instance : instances) {
            if (instance != null && Boolean.TRUE.equals(instance.getHealthy())
                    && Boolean.TRUE.equals(instance.getEnabled())) {
                available.add(instance);
            }
        }
        return available;
    }

    private ServiceInstance weightedRandom(List<ServiceInstance> instances) {
        double totalWeight = 0D;
        for (ServiceInstance instance : instances) {
            totalWeight += normalizedWeight(instance);
        }
        if (totalWeight <= 0D) {
            return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        }
        double cursor = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (ServiceInstance instance : instances) {
            cursor -= normalizedWeight(instance);
            if (cursor < 0D) {
                return instance;
            }
        }
        return instances.get(instances.size() - 1);
    }

    private double normalizedWeight(ServiceInstance instance) {
        Double weight = instance.getWeight();
        return weight == null || !Double.isFinite(weight) || weight <= 0D ? 1D : weight;
    }
}
