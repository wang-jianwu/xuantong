package cloud.xuantong.state.api;

import java.util.concurrent.CompletionStage;

public interface StateClient extends AutoCloseable {
    CompletionStage<ApplyResult> submit(StateCommand command);

    CompletionStage<QueryResult> query(StateQuery query);

    CompletionStage<WatchBatch> watch(WatchRequest request);

    @Override
    void close() throws Exception;
}
