package cloud.xuantong.server.admin.controller;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.config.management.content.ConfigContentResult;
import cloud.xuantong.config.management.content.ConfigContentService;
import cloud.xuantong.config.management.content.ConfigContentValidationException;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigRollout;
import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.server.state.management.ConfigStateManagementService;
import cloud.xuantong.server.state.management.ConfigStateWriteException;
import cloud.xuantong.server.state.management.ConfigStateWriteResult;
import cloud.xuantong.server.state.management.ConfigRolloutPreviewService;
import cloud.xuantong.server.state.management.ConfigRolloutPreviewService.ConfigRolloutPreview;
import cloud.xuantong.server.state.management.ConfigRolloutPreviewService.ConfigCurrentSelectionView;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import cloud.xuantong.config.management.service.ConfigResourceService;
import cloud.xuantong.config.management.service.ConfigDraftConflictException;
import cloud.xuantong.config.management.service.ConfigRolloutService;
import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.config.management.service.AuditLogService.AuditLogView;
import lombok.Getter;
import lombok.Setter;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Delete;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.annotation.Param;
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
    @Inject
    private ConfigContentService contentService;
    @Inject
    private ConfigRolloutPreviewService rolloutPreviewService;
    @Inject
    private AuditLogService auditLogService;

    @Get
    @Mapping
    public Result<PageResult<ConfigResource>> findByGroup(
            @Path String namespaceId,
            @Path String groupName,
            @Param(defaultValue = "") String keyword,
            @Param(defaultValue = "") String lifecycleStatus,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(configService.findPage(
                namespaceId, groupName, keyword, lifecycleStatus,
                new PageQuery(page, pageSize)));
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
    public Result<?> saveDraft(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Body SaveDraftRequest request,
            Context context) {
        ConfigResource resource = request == null ? new ConfigResource() : request.toResource();
        resource.setNamespaceId(namespaceId);
        resource.setGroupName(groupName);
        resource.setDataId(dataId);
        try {
            stateManagementService.assertDraftMutable(namespaceId, groupName, dataId);
            User user = AdminSecurityContext.currentUser(context);
            String operator = user == null ? "system" : user.getUsername();
            ConfigResource saved = configService.saveDraft(
                    resource,
                    request == null ? null : request.expectedDraftRevision,
                    operator);
            auditLogService.record(
                    namespaceId,
                    groupName,
                    "CONFIG",
                    dataId,
                    "CONFIG_DRAFT_SAVED",
                    operator,
                    java.util.Map.of(
                            "draftRevision", saved.getDraftRevision(),
                            "contentType", saved.getContentType(),
                            "checksum", saved.getChecksum()),
                    context.remoteIp(),
                    operationId(context));
            return Result.succeed(saved);
        } catch (ConfigContentValidationException e) {
            context.status(422);
            return Result.failure(422, "Config content validation failed", e.result());
        } catch (ConfigDraftConflictException e) {
            context.status(409);
            return Result.failure(409, e.getMessage(), DraftConflictView.from(e));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/content-tools")
    public Result<ConfigContentResult> contentTools(
            @Body ContentToolRequest request, Context context) {
        if (request == null) {
            return Result.failure("Content tool request is required");
        }
        ConfigContentResult result;
        String action = request.action == null ? "validate" : request.action.trim().toLowerCase();
        try {
            result = switch (action) {
                case "validate" -> contentService.validate(request.contentType, request.content);
                case "format" -> contentService.format(request.contentType, request.content);
                case "minify" -> contentService.minify(request.contentType, request.content);
                default -> throw new IllegalArgumentException("Unsupported content action: " + request.action);
            };
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
        if (!result.valid()) {
            context.status(422);
            return Result.failure(422, "Config content validation failed", result);
        }
        return Result.succeed(result);
    }

    @Delete
    @Mapping("/{dataId}")
    public Result<String> deleteDraft(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            Context context) {
        try {
            stateManagementService.assertDraftDeletable(namespaceId, groupName, dataId);
            boolean deleted = configService.deleteDraft(namespaceId, groupName, dataId);
            if (!deleted) {
                return Result.failure("Only unpublished drafts can be deleted");
            }
            User user = AdminSecurityContext.currentUser(context);
            auditLogService.record(
                    namespaceId,
                    groupName,
                    "CONFIG",
                    dataId,
                    "CONFIG_DRAFT_DELETED",
                    user == null ? "system" : user.getUsername(),
                    java.util.Map.of("dataId", dataId),
                    context.remoteIp(),
                    operationId(context));
            return Result.succeed("Draft deleted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{dataId}/releases")
    public Result<PageResult<ConfigRelease>> findReleases(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(configService.findReleasePage(
                namespaceId, groupName, dataId, new PageQuery(page, pageSize)));
    }

    @Get
    @Mapping("/{dataId}/rollouts")
    public Result<PageResult<ConfigRollout>> findRollouts(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(rolloutService.findRolloutPage(
                namespaceId, groupName, dataId, new PageQuery(page, pageSize)));
    }

    @Get
    @Mapping("/{dataId}/rollouts/current-selections")
    public Result<ConfigCurrentSelectionView> currentRolloutSelections(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId) {
        try {
            return Result.succeed(rolloutPreviewService.currentSelections(
                    namespaceId, groupName, dataId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{dataId}/audits")
    public Result<PageResult<AuditLogView>> findAudits(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(configService.findAuditPage(
                namespaceId, groupName, dataId, new PageQuery(page, pageSize))
                .map(auditLogService::view));
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
            User user = AdminSecurityContext.currentUser(context);
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
            User user = AdminSecurityContext.currentUser(context);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.publish(
                    namespaceId, groupName, dataId, operator, operationId(context)).release());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/tombstone")
    public Result<ConfigRelease> tombstone(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            Context context) {
        try {
            User user = AdminSecurityContext.currentUser(context);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(stateManagementService.tombstone(
                    namespaceId,
                    groupName,
                    dataId,
                    operator,
                    operationId(context)).release());
        } catch (IllegalArgumentException | IllegalStateException | ConfigStateWriteException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/rollouts/preview")
    public Result<ConfigRolloutPreview> previewRollout(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Body RolloutRequest request) {
        try {
            ConfigResource resource = configService.find(namespaceId, groupName, dataId);
            if (resource == null || resource.getRevision() == null
                    || resource.getRevision() < 1) {
                throw new IllegalStateException(
                        "Publish a stable Config release before previewing a rollout");
            }
            return Result.succeed(rolloutPreviewService.preview(
                    namespaceId,
                    groupName,
                    dataId,
                    policyOf(request),
                    request == null ? null : request.getRolloutKey()));
        } catch (IllegalArgumentException | IllegalStateException e) {
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
            User user = AdminSecurityContext.currentUser(context);
            String operator = user == null ? "system" : user.getUsername();
            ConfigRolloutPolicy policy = policyOf(request);
            return Result.succeed(stateManagementService.startRollout(
                    namespaceId,
                    groupName,
                    dataId,
                    policy,
                    request == null ? null : request.getRolloutKey(),
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
            User user = AdminSecurityContext.currentUser(context);
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
            User user = AdminSecurityContext.currentUser(context);
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
            User user = AdminSecurityContext.currentUser(context);
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
        private List<String> clientInstanceIds;
        private Integer percentage;
        private String rolloutKey;
    }

    public static class SaveDraftRequest {
        public String content;
        public String contentType;
        public String description;
        public Long expectedDraftRevision;

        ConfigResource toResource() {
            ConfigResource resource = new ConfigResource();
            resource.setContent(content);
            resource.setContentType(contentType);
            resource.setDescription(description);
            return resource;
        }
    }

    public static class ContentToolRequest {
        public String action;
        public String contentType;
        public String content;
    }

    public record DraftConflictView(
            long expectedDraftRevision,
            long actualDraftRevision,
            String submittedContent,
            String submittedContentType,
            String submittedDescription,
            String currentContent,
            String currentContentType,
            String currentDescription) {

        static DraftConflictView from(ConfigDraftConflictException conflict) {
            ConfigResource submitted = conflict.submitted();
            ConfigResource current = conflict.current();
            return new DraftConflictView(
                    conflict.expectedDraftRevision(),
                    conflict.actualDraftRevision(),
                    submitted == null ? null : submitted.getContent(),
                    submitted == null ? null : submitted.getContentType(),
                    submitted == null ? null : submitted.getDescription(),
                    current == null ? null : current.getContent(),
                    current == null ? null : current.getContentType(),
                    current == null ? null : current.getDescription());
        }
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
            case GRAY_CLIENT_INSTANCE -> ConfigRolloutPolicy.clientInstances(
                    request.getClientInstanceIds());
            case GRAY_PERCENTAGE -> ConfigRolloutPolicy.percentage(request.getPercentage());
            default -> throw new IllegalArgumentException(
                    "Rollout type must be GRAY_IP, GRAY_CLIENT_INSTANCE or GRAY_PERCENTAGE");
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
