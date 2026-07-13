package cloud.xuantong.client.listener;

import cloud.xuantong.client.model.ServiceChangeEvent;

@FunctionalInterface
public interface ServiceListener {
    void onServiceChange(ServiceChangeEvent event);
}
