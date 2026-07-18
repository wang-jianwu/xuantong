package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.protocol.exceptions.AlreadyClosedException;
import org.apache.ratis.protocol.exceptions.GroupMismatchException;
import org.apache.ratis.protocol.exceptions.LeaderNotReadyException;
import org.apache.ratis.protocol.exceptions.NotLeaderException;
import org.apache.ratis.protocol.exceptions.RaftRetryFailureException;
import org.apache.ratis.protocol.exceptions.StateMachineException;
import org.apache.ratis.protocol.exceptions.TimeoutIOException;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

final class RatisStateFailureMapper {
    private RatisStateFailureMapper() {
    }

    static StateAccessException map(
            Throwable failure,
            StateGroupId groupId,
            boolean write,
            boolean knownLeader) {
        StateAccessException existing = find(failure, StateAccessException.class);
        if (existing != null) {
            return existing;
        }

        StateCommitStatus commitStatus = write
                ? StateCommitStatus.UNKNOWN
                : StateCommitStatus.NOT_APPLICABLE;
        StateMachineException stateMachine = find(failure, StateMachineException.class);
        if (stateMachine != null) {
            return StateAccessException.nonRetryable(
                    StateFailureCode.INTERNAL_ERROR,
                    groupId,
                    "Replicated state machine rejected the operation unexpectedly",
                    commitStatus,
                    stateMachine);
        }

        Throwable timeout = first(failure, TimeoutIOException.class, TimeoutException.class);
        if (timeout != null) {
            StateFailureCode code = write && knownLeader
                    ? StateFailureCode.NO_QUORUM
                    : StateFailureCode.STATE_UNAVAILABLE;
            return StateAccessException.retryable(
                    code,
                    groupId,
                    code == StateFailureCode.NO_QUORUM
                            ? "Known Raft leader could not commit before the proposal deadline"
                            : "State read or routing deadline expired",
                    commitStatus,
                    timeout);
        }

        Throwable unavailable = first(
                failure,
                NotLeaderException.class,
                LeaderNotReadyException.class,
                RaftRetryFailureException.class,
                AlreadyClosedException.class,
                GroupMismatchException.class);
        if (unavailable != null) {
            return StateAccessException.retryable(
                    StateFailureCode.STATE_UNAVAILABLE,
                    groupId,
                    "No usable State Group leader is available",
                    commitStatus,
                    unavailable);
        }

        RatisOperationException operation = find(failure, RatisOperationException.class);
        if (operation != null && operation.getCause() == null) {
            return StateAccessException.nonRetryable(
                    StateFailureCode.INTERNAL_ERROR,
                    groupId,
                    operation.getMessage(),
                    commitStatus,
                    operation);
        }

        IOException io = find(failure, IOException.class);
        if (io != null) {
            return StateAccessException.retryable(
                    StateFailureCode.STATE_UNAVAILABLE,
                    groupId,
                    "State transport is unavailable",
                    commitStatus,
                    io);
        }

        Throwable root = unwrap(failure);
        return StateAccessException.nonRetryable(
                StateFailureCode.INTERNAL_ERROR,
                groupId,
                "Unexpected State client failure",
                commitStatus,
                root);
    }

    static boolean requiresClientRebuild(Throwable failure) {
        return find(failure, AlreadyClosedException.class) != null
                || find(failure, RaftRetryFailureException.class) != null;
    }

    @SafeVarargs
    private static Throwable first(
            Throwable failure, Class<? extends Throwable>... types) {
        for (Class<? extends Throwable> type : types) {
            Throwable found = find(failure, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
