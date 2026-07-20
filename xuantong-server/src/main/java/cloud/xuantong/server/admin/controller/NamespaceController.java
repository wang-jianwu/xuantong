package cloud.xuantong.server.admin.controller;

import cloud.xuantong.security.model.User;
import cloud.xuantong.resource.model.ConfigNamespace;
import cloud.xuantong.resource.model.ResourceGroup;
import cloud.xuantong.resource.service.NamespaceService;
import cloud.xuantong.security.service.AuthorizationService;
import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Path;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.List;
import java.util.Map;

@Controller
@Mapping("/api/v2/namespaces")
public class NamespaceController {
    @Inject
    private NamespaceService namespaceService;
    @Inject private AuthorizationService authorizationService;
    @Inject private AuditLogService auditLogService;

    @Get
    @Mapping
    public Result<List<ConfigNamespace>> findAll(Context context) {
        User user = AdminSecurityContext.currentUser(context);
        java.util.Set<String> allowed = authorizationService.authorizedNamespaces(user);
        List<ConfigNamespace> namespaces = namespaceService.findAll();
        return Result.succeed(allowed.contains("*") ? namespaces : namespaces.stream()
                .filter(item -> allowed.contains(item.getNamespaceId())).toList());
    }

    @Post
    @Mapping
    public Result<ConfigNamespace> create(@Body ConfigNamespace namespace, Context context) {
        try {
            String operator = currentUser(context);
            ConfigNamespace created = namespaceService.create(namespace, operator);
            auditLogService.record(
                    created.getNamespaceId(), null, "NAMESPACE", created.getNamespaceId(),
                    "NAMESPACE_CREATED", operator,
                    Map.of("name", created.getName(), "active", created.getIsActive()),
                    context.remoteIp(), null);
            return Result.succeed(created);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Get
    @Mapping("/{namespaceId}/groups")
    public Result<List<ResourceGroup>> findGroups(@Path String namespaceId) {
        return Result.succeed(namespaceService.findGroups(namespaceId));
    }

    @Post
    @Mapping("/{namespaceId}/groups")
    public Result<ResourceGroup> createGroup(
            @Path String namespaceId, @Body ResourceGroup group, Context context) {
        try {
            String operator = currentUser(context);
            ResourceGroup created = namespaceService.createGroup(
                    namespaceId, group, operator);
            auditLogService.record(
                    created.getNamespaceId(), created.getGroupName(), "RESOURCE_GROUP",
                    created.getGroupName(), "RESOURCE_GROUP_CREATED", operator,
                    Map.of("namespaceId", created.getNamespaceId(),
                            "groupName", created.getGroupName()),
                    context.remoteIp(), null);
            return Result.succeed(created);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    private String currentUser(Context context) {
        User user = AdminSecurityContext.currentUser(context);
        return user == null ? "system" : user.getUsername();
    }
}
