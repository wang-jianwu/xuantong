package cloud.xuantong.integration.spring.cloud.discovery;

/** Provides and owns service-scoped Discovery Agents. */
public interface XuantongDiscoveryClientProvider extends AutoCloseable {
    XuantongDiscoveryOperations get(String serviceName);

    XuantongDiscoveryOperations getIfPresent(String serviceName);

    @Override
    void close();
}
