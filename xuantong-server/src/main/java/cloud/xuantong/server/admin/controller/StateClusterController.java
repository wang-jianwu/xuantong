package cloud.xuantong.server.admin.controller;

import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.raft.ratis.RatisMembershipChangeResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import cloud.xuantong.server.state.management.StateClusterManagementService;
import cloud.xuantong.server.state.management.StateClusterManagementService.MembershipChangeRequest;
import cloud.xuantong.server.state.management.StateClusterManagementService.StateClusterStatus;
import cloud.xuantong.server.state.management.StateSnapshotService;
import cloud.xuantong.server.state.management.StateSnapshotService.SnapshotBatchRequest;
import cloud.xuantong.server.state.management.StateSnapshotService.SnapshotBatchResult;
import cloud.xuantong.server.state.management.StateProjectionConsistencyService;
import cloud.xuantong.server.state.management.StateProjectionConsistencyService.ConsistencyReport;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.Map;

@Controller
@Mapping("/api/v2/state-cluster")
public final class StateClusterController {
    @Inject
    private StateClusterManagementService stateCluster;
    @Inject
    private AuditLogService auditLogService;
    @Inject
    private StateSnapshotService stateSnapshots;
    @Inject
    private StateProjectionConsistencyService consistencyService;

    @Get
    @Mapping
    public Result<StateClusterStatus> status() {
        try {
            return Result.succeed(stateCluster.status());
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/consistency")
    public Result<ConsistencyReport> consistency() {
        try {
            return Result.succeed(consistencyService.check());
        } catch (Exception e) {
            return Result.failure(safeMessage(e));
        }
    }

    @Post
    @Mapping("/membership")
    public Result<RatisMembershipChangeResult> change(
            @Body MembershipChangeRequest request, Context context) {
        String operator = currentUser(context);
        try {
            RatisMembershipChangeResult changed = stateCluster.change(request);
            auditLogService.record(
                    null,
                    null,
                    "STATE_CLUSTER",
                    "compact-state-cluster",
                    "RAFT_MEMBERSHIP_CHANGED",
                    operator,
                    Map.of(
                            "previousVoters", changed.previousVoters(),
                            "targetVoters", changed.targetVoters(),
                            "groups", changed.groups()),
                    context.remoteIp(),
                    request.operationId());
            return Result.succeed(changed);
        } catch (Exception e) {
            auditLogService.record(
                    null,
                    null,
                    "STATE_CLUSTER",
                    "compact-state-cluster",
                    "RAFT_MEMBERSHIP_CHANGE_FAILED",
                    operator,
                    Map.of("error", safeMessage(e)),
                    context.remoteIp(),
                    request == null ? null : request.operationId());
            return Result.failure(safeMessage(e));
        }
    }

    @Post
    @Mapping("/snapshot")
    public Result<SnapshotBatchResult> snapshot(
            @Body SnapshotBatchRequest request, Context context) {
        String operator = currentUser(context);
        try {
            SnapshotBatchResult result = stateSnapshots.force(request);
            auditLogService.record(
                    null,
                    null,
                    "STATE_CLUSTER",
                    result.targetNodeId(),
                    "RAFT_SNAPSHOT_FORCED",
                    operator,
                    Map.of(
                            "capturedAtEpochMs", result.capturedAtEpochMs(),
                            "groups", result.groups()),
                    context.remoteIp(),
                    result.operationId());
            return Result.succeed(result);
        } catch (Exception e) {
            auditLogService.record(
                    null,
                    null,
                    "STATE_CLUSTER",
                    request == null ? "unknown" : request.targetNodeId(),
                    "RAFT_SNAPSHOT_FORCE_FAILED",
                    operator,
                    Map.of("error", safeMessage(e)),
                    context.remoteIp(),
                    request == null ? null : request.operationId());
            return Result.failure(safeMessage(e));
        }
    }

    private static String currentUser(Context context) {
        User user = AdminSecurityContext.currentUser(context);
        return user == null ? "system" : user.getUsername();
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
