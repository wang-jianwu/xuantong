package cloud.xuantong.state.api;

import java.util.Set;

public interface StateNode extends AutoCloseable {
    String nodeId();

    Set<StateGroupId> hostedGroups();

    boolean isRunning();

    void start() throws Exception;

    @Override
    void close() throws Exception;
}
