package cloud.xuantong.server.state.management;

import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.ResolvedConfigOperation;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;

public interface ConfigStateAccess {
    boolean available();

    StateGroupId groupId();

    ReleaseDecision currentDecision(ConfigKey key);

    ApplyResult submit(StateCommand command);

    ResolvedConfigOperation resolve(ConfigActor actor, String operationId);
}
