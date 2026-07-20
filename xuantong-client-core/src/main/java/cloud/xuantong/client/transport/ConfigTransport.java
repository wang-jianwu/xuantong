package cloud.xuantong.client.transport;

import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ConfigGroupSnapshot;
import cloud.xuantong.client.model.ConfigWatchBatch;

import java.util.Collection;
import java.util.List;

public interface ConfigTransport extends AutoCloseable {
    void connect(List<String> serverAddresses,
                 String namespace,
                 String group,
                 String accessToken);

    ConfigSnapshot fetch(String dataId, long minDecisionRevision);

    ConfigGroupSnapshot snapshot(
            Collection<String> dataIds, long minEventRevision);

    ConfigWatchBatch watchBatch(
            Collection<String> dataIds,
            long afterEventRevision,
            int maxBatchSize);

    default WatchSubscription subscribe(
            long afterEventRevision,
            WatchBatchHandler<ConfigWatchBatch> handler) {
        throw new UnsupportedOperationException("Config Watch subscription is not supported");
    }

    @Override
    void close();

    default void setOnReconnect(Runnable listener) {
    }
}
