package cloud.xuantong.client.transport;

public interface WatchSubscription extends AutoCloseable {
    @Override
    void close();
}
