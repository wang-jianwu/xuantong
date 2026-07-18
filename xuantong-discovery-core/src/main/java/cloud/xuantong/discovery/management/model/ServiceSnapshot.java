package cloud.xuantong.discovery.management.model;

import cloud.xuantong.resource.model.ServiceKey;

import java.util.List;

public record ServiceSnapshot(ServiceKey service, long revision, List<ServiceInstance> instances) {
}
