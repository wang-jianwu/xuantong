package cloud.xuantong.client.transport;

@FunctionalInterface
public interface WatchBatchHandler<T> {
    /** Applies one batch and returns the highest durably accepted cursor. */
    long onBatch(T batch) throws Exception;

    default void onError(Throwable error) {
    }
}
