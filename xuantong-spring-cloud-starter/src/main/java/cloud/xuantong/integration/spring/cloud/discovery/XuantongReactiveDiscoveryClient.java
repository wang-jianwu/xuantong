package cloud.xuantong.integration.spring.cloud.discovery;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Reactive DiscoveryClient facade over Xuantong's shared discovery connection. */
public class XuantongReactiveDiscoveryClient implements ReactiveDiscoveryClient {
    private final XuantongDiscoveryClientProvider manager;
    private final XuantongServiceInstanceMapper mapper;
    private final String namespace;
    private final String group;

    public XuantongReactiveDiscoveryClient(
            XuantongDiscoveryClientProvider manager,
            XuantongServiceInstanceMapper mapper,
            String namespace,
            String group) {
        this.manager = manager;
        this.mapper = mapper;
        this.namespace = namespace;
        this.group = group;
    }

    @Override
    public String description() {
        return "Xuantong ReactiveDiscoveryClient[namespace=" + namespace
                + ", group=" + group + "]";
    }

    @Override
    public Flux<ServiceInstance> getInstances(String serviceId) {
        return Flux.defer(() -> Flux.fromIterable(
                        manager.get(serviceId).getInstances().stream()
                                .map(mapper::toSpring)
                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<String> getServices() {
        return Flux.defer(() -> Flux.fromIterable(manager.getServices()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
