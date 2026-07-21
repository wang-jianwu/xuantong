package cloud.xuantong.integration.spring.cloud.discovery;

import java.util.List;

/** Provides and owns service-scoped Discovery Agents. */
public interface XuantongDiscoveryClientProvider extends AutoCloseable {
    XuantongDiscoveryOperations get(String serviceName);

    XuantongDiscoveryOperations getIfPresent(String serviceName);

    List<String> getServices();

    @Override
    void close();
}
