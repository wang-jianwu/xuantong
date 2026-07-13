package cloud.xuantong.admin.controller.v2;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.v2.model.ConfigResource;
import cloud.xuantong.core.v2.model.ConfigRelease;
import cloud.xuantong.core.v2.model.AuditLog;
import cloud.xuantong.core.v2.model.ReleaseType;
import cloud.xuantong.core.v2.event.ConfigReleaseEvent;
import cloud.xuantong.core.v2.service.ConfigResourceServiceV2;
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
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.event.EventBus;

import java.util.List;

@Controller
@Mapping("/api/v2/namespaces/{namespaceId}/groups/{groupName}/configs")
public class ConfigResourceControllerV2 {
    @Inject
    private ConfigResourceServiceV2 configService;

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
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            return Result.succeed(configService.saveDraft(resource, operator));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete
    @Mapping("/{dataId}")
    public Result<String> deleteDraft(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        return configService.deleteDraft(namespaceId, groupName, dataId)
                ? Result.succeed("Draft deleted")
                : Result.failure("Only unpublished drafts can be deleted");
    }

    @Get
    @Mapping("/{dataId}/releases")
    public Result<List<ConfigRelease>> findReleases(
            @Path String namespaceId, @Path String groupName, @Path String dataId) {
        return Result.succeed(configService.findReleases(namespaceId, groupName, dataId));
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
            @Param(defaultValue = "FULL") String releaseType,
            Context context) {
        try {
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            List<ConfigRelease> releases = configService.publishBatch(
                    namespaceId,
                    groupName,
                    request == null ? null : request.getDataIds(),
                    ReleaseType.valueOf(releaseType.trim().toUpperCase()),
                    operator);
            releases.forEach(release -> EventBus.publish(
                    new ConfigReleaseEvent("CONFIG_PUBLISHED", release)));
            return Result.succeed(releases);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Post
    @Mapping("/{dataId}/publish")
    public Result<ConfigRelease> publish(
            @Path String namespaceId,
            @Path String groupName,
            @Path String dataId,
            @Param(defaultValue = "FULL") String releaseType,
            Context context) {
        try {
            User user = context.session("user", User.class);
            String operator = user == null ? "system" : user.getUsername();
            ConfigRelease release = configService.publish(
                    namespaceId, groupName, dataId,
                    ReleaseType.valueOf(releaseType.trim().toUpperCase()), operator);
            EventBus.publish(new ConfigReleaseEvent("CONFIG_PUBLISHED", release));
            return Result.succeed(release);
        } catch (IllegalArgumentException | IllegalStateException e) {
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
            ConfigRelease release = configService.rollback(
                    namespaceId, groupName, dataId, releaseId, operator);
            EventBus.publish(new ConfigReleaseEvent("CONFIG_ROLLED_BACK", release));
            return Result.succeed(release);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Setter
    @Getter
    public static class BatchPublishRequest {
        private List<String> dataIds;

    }
}
