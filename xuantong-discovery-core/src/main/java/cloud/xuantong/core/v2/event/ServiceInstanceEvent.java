package cloud.xuantong.core.v2.event;

import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.model.ServiceKey;

public record ServiceInstanceEvent(
        String eventType, ServiceKey service, long revision, ServiceInstance instance) {
}
