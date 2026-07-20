package cloud.xuantong.gateway.socketd;

import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Applies the Gateway's remaining monotonic deadline to one StateClient call.
 * Timing out this wrapper never cancels the underlying State operation; a write
 * may still commit and must be resolved by operationId.
 */
public final class ControlPlaneStateExecutor {
    private final StateClient stateClient;

    public ControlPlaneStateExecutor(StateClient stateClient) {
        if (stateClient == null) {
            throw new IllegalArgumentException("stateClient must not be null");
        }
        this.stateClient = stateClient;
    }

    public CompletionStage<ApplyResult> submit(
            StateCommand command, ControlPlaneRequestContext context) {
        return execute(
                () -> stateClient.submit(command), context, command.groupId(), true);
    }

    public CompletionStage<QueryResult> query(
            StateQuery query, ControlPlaneRequestContext context) {
        return execute(
                () -> stateClient.query(query), context, query.groupId(), false);
    }

    public CompletionStage<WatchBatch> watch(
            WatchRequest request, ControlPlaneRequestContext context) {
        return execute(
                () -> stateClient.watch(request), context, request.groupId(), false);
    }

    private <T> CompletionStage<T> execute(
            Supplier<CompletionStage<T>> operation,
            ControlPlaneRequestContext context,
            StateGroupId groupId,
            boolean write) {
        if (context.remainingBudgetNanos() == 0L) {
            return CompletableFuture.failedFuture(deadlineFailure(groupId, write));
        }
        try {
            return withinBudget(operation.get(), context, groupId, write);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    normalizeFailure(e, groupId, write));
        }
    }

    private <T> CompletionStage<T> withinBudget(
            CompletionStage<T> source,
            ControlPlaneRequestContext context,
            StateGroupId groupId,
            boolean write) {
        if (source == null) {
            return CompletableFuture.failedFuture(StateAccessException.nonRetryable(
                    StateFailureCode.INTERNAL_ERROR,
                    groupId,
                    "StateClient returned no CompletionStage",
                    commitStatus(write),
                    null));
        }
        long remainingNanos = context.remainingBudgetNanos();
        if (remainingNanos == 0) {
            return CompletableFuture.failedFuture(deadlineFailure(groupId, write));
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(normalizeFailure(
                        failure, groupId, write));
            }
        });
        CompletableFuture.delayedExecutor(
                        remainingNanos, TimeUnit.NANOSECONDS)
                .execute(() -> result.completeExceptionally(
                        deadlineFailure(groupId, write)));
        return result;
    }

    private StateAccessException normalizeFailure(
            Throwable failure, StateGroupId groupId, boolean write) {
        Throwable unwrapped = unwrap(failure);
        if (unwrapped instanceof StateAccessException stateFailure) {
            if (!groupId.equals(stateFailure.groupId())) {
                return StateAccessException.nonRetryable(
                        StateFailureCode.INTERNAL_ERROR,
                        groupId,
                        "StateClient returned a failure for another State Group",
                        commitStatus(write),
                        stateFailure);
            }
            if (stateFailure.code() != StateFailureCode.NOT_LEADER) {
                return stateFailure;
            }
            return StateAccessException.retryable(
                    StateFailureCode.STATE_UNAVAILABLE,
                    groupId,
                    "State leader routing did not converge within the request budget",
                    stateFailure.commitStatus(),
                    stateFailure);
        }
        return StateAccessException.nonRetryable(
                StateFailureCode.INTERNAL_ERROR,
                groupId,
                "Unexpected State client failure",
                commitStatus(write),
                unwrapped);
    }

    private StateAccessException deadlineFailure(StateGroupId groupId, boolean write) {
        return StateAccessException.retryable(
                StateFailureCode.DEADLINE_EXCEEDED,
                groupId,
                "Gateway State routing exceeded the remaining request budget",
                commitStatus(write),
                null);
    }

    private StateCommitStatus commitStatus(boolean write) {
        return write ? StateCommitStatus.UNKNOWN : StateCommitStatus.NOT_APPLICABLE;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
