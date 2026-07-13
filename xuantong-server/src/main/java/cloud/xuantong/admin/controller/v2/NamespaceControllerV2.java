package cloud.xuantong.admin.controller.v2;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.v2.model.ConfigNamespace;
import cloud.xuantong.core.v2.model.ResourceGroup;
import cloud.xuantong.core.v2.service.NamespaceServiceV2;
import cloud.xuantong.core.service.AuthorizationService;
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

@Controller
@Mapping("/api/v2/namespaces")
public class NamespaceControllerV2 {
    @Inject
    private NamespaceServiceV2 namespaceService;
    @Inject private AuthorizationService authorizationService;

    @Get
    @Mapping
    public Result<List<ConfigNamespace>> findAll(Context context) {
        User user = context.session("user", User.class);
        java.util.Set<String> allowed = authorizationService.authorizedNamespaces(user);
        List<ConfigNamespace> namespaces = namespaceService.findAll();
        return Result.succeed(allowed.contains("*") ? namespaces : namespaces.stream()
                .filter(item -> allowed.contains(item.getNamespaceId())).toList());
    }

    @Post
    @Mapping
    public Result<ConfigNamespace> create(@Body ConfigNamespace namespace, Context context) {
        try {
            return Result.succeed(namespaceService.create(namespace, currentUser(context)));
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
            return Result.succeed(namespaceService.createGroup(namespaceId, group, currentUser(context)));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }

    private String currentUser(Context context) {
        User user = context.session("user", User.class);
        return user == null ? "system" : user.getUsername();
    }
}
