package cloud.xuantong.core.v2.model;

import java.util.List;

public record ServiceSnapshot(ServiceKey service, long revision, List<ServiceInstance> instances) {
}
