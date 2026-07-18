package cloud.xuantong.state.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Deterministic, single-group business state machine.
 *
 * <p>{@link #apply(StateCommand, ApplyContext)} must not access a network,
 * database repository, Socket session, random source, or wall clock. Apply is
 * invoked serially for one Group; query and Watch implementations must support
 * concurrent reads. Snapshot methods must capture and restore a complete,
 * schema-versioned state without closing the supplied stream.</p>
 */
public interface StateMachine {
    StateGroupId groupId();

    ApplyResult apply(StateCommand command, ApplyContext context);

    QueryResult query(StateQuery query);

    WatchBatch watch(WatchRequest request);

    int snapshotSchemaVersion();

    void writeSnapshot(OutputStream output) throws IOException;

    void installSnapshot(int schemaVersion, InputStream input) throws IOException;
}
