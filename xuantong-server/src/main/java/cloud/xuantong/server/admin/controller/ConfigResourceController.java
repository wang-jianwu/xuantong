package cloud.xuantong.server.admin.controller;

import cloud.xuantong.security.model.User;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.server.state.management.ConfigStateManagementService;
import cloud.xuantong.server.state.management.ConfigStateWriteException;
import cloud.xuantong.server.state.management.ConfigStateWriteResult;
import cloud.xuantong.config.management.service.ConfigResourceService;
import cloud.xuantong.config.management.service.ConfigRolloutService;
import lombok.Getter;
import lombok.Setter;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.annotation.Put;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Controller
@Mapping("/api/v2/namespaces/{namespaceId}/groups/{groupName}/configs")
public class ConfigResourceController {
    @Inject
    private ConfigResourceService configService;
    @Inject
    private ConfigRolloutService rolloutService;
    @Inject
    private ConfigStateManagementService stateManagementService;

    @Get
    @Mapping
    public Result<List<ConfigResource>> findByGroup(
            @Path String namespaceId, @Path String groupName) {
        return Result.succeed(configService.findByGroup(namespaceId, groupName));
    }

    @Get
    @Mapping("/{dataId}")
    public Result<ConfigResource> find(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        ConfigResource resource = configService.find(namespaceId, groupName, dataId);
        return resource == null ? Result.failure("Config resource does not exist") : Result.succeed(resource);
    }

    @Put
    @Mapping("/{dataId}")
    public Result<ConfigResource> saveDraft(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Body ConfigResource resource,
            Context context) {
        resource.setNamespaceId(namespaceId);
        resource.setGroupName(groupName);
        resource.setDataId(dataId);
        try {
            stateManagementService.assertDraftMutable(namespaceId, groupName, dataId);
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(configService.saveDraft(resource, operator));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete
    @Mapping("/{dataId}")
    public Result<String> deleteDraft(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        try {
            stateManagementService.assertDraftDeletable(namespaceId, groupName, dataId);
            return configService.deleteDraft(namespaceId, groupName, dataId)
                    ? Result.succeed("Draft deleted")
                    : Result.failure("Only unpublished drafts can be deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{dataId}/releases")
    public Result<List<ConfigRelease>> findReleases(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        return Result.succeed(configService.findReleases(namespaceId, groupName, dataId));
    }

    @Get
    @Mapping("/{dataId}/rollouts")
    public Result<List<ConfigRollout>> findRollouts(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        return Result.succeed(rolloutService.findRollouts(namespaceId, groupName, dataId));
    }

    @Get
    @Mapping("/{dataId}/audits")
    public Result<List<AuditLog>> findAudits(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        return Result.succeed(configService.findAudits(namespaceId, groupName, dataId));
    }

    @Post
    @Mapping("/batch-publish")
    public Result<List<ConfigRelease>> publishBatch(
            @Path String namespaceId,
            @Path String groupName,
            @Body BatchPublishRequest request,
            Context context) {
        try {
            rejectLegacyReleaseType(context);
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            List<String> dataIds = request == null ? null : request.getDataIds();
            if (dataIds == null || dataIds.isEmpty()) {
                throw new IllegalArgumentException("dataIds must not be empty");
            }
            if (dataIds.size() > 100) {
                throw new IllegalArgumentException("A batch can publish at most 100 configs");
            }
            Set<String> uniqueDataIds = new HashSet<>();
            for (String dataId : dataIds) {
                if (dataId == null || dataId.isBlank()) {
                    throw new IllegalArgumentException("Batch dataId must not be blank");
                }
                if (!uniqueDataIds.add(dataId.trim())) {
                    throw new IllegalArgumentException(
                            "Duplicate dataId in batch: " + dataId.trim());
                }
            }
            String batchOperationId = operationId(context);
            if (batchOperationId.length() > 110) {
                throw new IllegalArgumentException(
                        "Batch X-Xuantong-Operation-Id must not exceed 110 characters");
            }
            List<ConfigRelease> releases = new ArrayList<>(dataIds.size());
            for (int i = 0; i < dataIds.size(); i++) {
                ConfigStateWriteResult result = stateManagementService.publish(
                        namespaceId,
                        groupName,
                        dataIds.get(i),
                        operator,
                        batchOperationId + ":" + i);
                releases.add(result.release());
            }
            return Result.succeed(releases);
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/publish")
    public Result<ConfigRelease> publish(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            Context context) {
        try {
            rejectLegacyReleaseType(context);
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.publish(
                    namespaceId, groupName, dataId, operator, operationId(context)).release());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/rollouts")
    public Result<ConfigRollout> startRollout(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Body RolloutRequest request,
            Context context) {
        try {
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.startRollout(
                    namespaceId,
                    groupName,
                    dataId,
                    policyOf(request),
                    operator,
                    operationId(context)).rollout());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/rollouts/{rolloutId}/promote")
    public Result<ConfigRollout> promoteRollout(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Path String rolloutId,
            Context context) {
        try {
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.promoteRollout(
                    namespaceId,
                    groupName,
                    dataId,
                    rolloutId,
                    operator,
                    operationId(context)).rollout());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/rollouts/{rolloutId}/abort")
    public Result<ConfigRollout> abortRollout(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Path String rolloutId,
            Context context) {
        try {
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.abortRollout(
                    namespaceId,
                    groupName,
                    dataId,
                    rolloutId,
                    operator,
                    operationId(context)).rollout());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/rollback/{releaseId}")
    public Result<ConfigRelease> rollback(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Path String releaseId,
            Context context) {
        try {
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.rollback(
                    namespaceId,
                    groupName,
                    dataId,
                    releaseId,
                    operator,
                    operationId(context)).release());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Setter
    @Getter
    public static class BatchPublishRequest {
        private List<String> dataIds;

    }

    @Setter
    @Getter
    public static class RolloutRequest {
        private String type;
        private List<String> ips;
        private Integer percentage;
    }

    private ConfigRolloutPolicy policyOf(RolloutRequest request) {
        if (request == null || request.getType() == null) {
            throw new IllegalArgumentException("Rollout type is required");
        }
        ReleaseType type;
        try {
            type = ReleaseType.valueOf(request.getType().trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported rollout type: " + request.getType());
        }
        return switch (type) {
            case GRAY_IP -> ConfigRolloutPolicy.ip(request.getIps());
            case GRAY_PERCENTAGE -> ConfigRolloutPolicy.percentage(request.getPercentage());
            default -> throw new IllegalArgumentException(
                    "Rollout type must be GRAY_IP or GRAY_PERCENTAGE");
        };
    }

    private void rejectLegacyReleaseType(Context context) {
        String releaseType = context.param("releaseType");
        if (releaseType != null && !releaseType.isBlank()) {
            throw new IllegalArgumentException(
                    "releaseType query parameter was removed in 2.0; use /rollouts for gray release");
        }
    }

    private String operationId(Context context) {
        return context.header("X-Xuantong-Operation-Id");
    }
}
