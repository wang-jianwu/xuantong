package cloud.xuantong.server.admin.controller;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.service.UserService;
import cloud.xuantong.security.service.AuthorizationService;
import cloud.xuantong.security.model.UserScopeRole;
import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@Mapping("/api/user")
public class UserController {
    @Inject
    private UserService userService;
    @Inject private AuthorizationService authorizationService;
    @Inject private AuditLogService auditLogService;

    /**
     * 将 User 实体转为不含密码字段的 Map，防止密码哈希泄露
     */
    private Map<String, Object> toUserMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("email", user.getEmail());
        map.put("realName", user.getRealName());
        map.put("role", user.getRole());
        map.put("isActive", user.getIsActive());
        map.put("createdAt", user.getCreatedAt());
        map.put("lastLoginTime", user.getLastLoginTime());
        return map;
    }

    @Get
    @Mapping
    public Result<PageResult<Map<String, Object>>> getAllUsers(
            @Param(defaultValue = "") String keyword,
            @Param(defaultValue = "") String role,
            @Param(required = false) Boolean active,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(userService.findPage(
                keyword, role, active, new PageQuery(page, pageSize))
                .map(this::toUserMap));
    }

    @Get
    @Mapping("/{id}")
    public Result<Map<String, Object>> getUserById(@Path Long id) {
        User user = userService.getUserById(id);
        return user != null ? Result.succeed(toUserMap(user)) : Result.failure("用户不存在");
    }

    @Post
    @Mapping
    public Result<String> createUser(@Body User user, Context ctx) {
        // 非 admin 不允许创建用户
        User currentUser = AdminSecurityContext.currentUser(ctx);
        if (!authorizationService.isSystemAdmin(currentUser)) {
            return Result.failure("权限不足");
        }
        try {
            boolean success = userService.createUser(user);
            if (success) {
                auditLogService.record(
                        null, null, "USER", user.getUsername(), "USER_CREATED",
                        operator(ctx), Map.of("role", String.valueOf(user.getRole())),
                        ctx.remoteIp(), null);
            }
            return success ? Result.succeed("创建成功") : Result.failure("创建失败");
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Put
    @Mapping
    public Result<String> updateUser(@Body User user, Context ctx) {
        // 非 admin 不允许修改他人信息，也不允许修改角色
        User currentUser = AdminSecurityContext.currentUser(ctx);
        if (currentUser != null && !authorizationService.isSystemAdmin(currentUser)) {
            // 普通用户只能修改自己的信息，且不能修改角色
            if (!currentUser.getId().equals(user.getId())) {
                return Result.failure("只能修改自己的信息");
            }
            user.setRole(currentUser.getRole()); // 强制保持原角色
        }
        boolean success = userService.updateUser(user);
        if (success) {
            auditLogService.record(
                    null, null, "USER", String.valueOf(user.getId()), "USER_UPDATED",
                    operator(ctx), Map.of(
                            "userId", user.getId(),
                            "role", String.valueOf(user.getRole())),
                    ctx.remoteIp(), null);
        }
        return success ? Result.succeed("更新成功") : Result.failure("更新失败");
    }

    @Put
    @Mapping("/active/{userId}/{active}")
    public Result<String> setUserActive(
            @Path Long userId,
            @Path boolean active,
            Context context) {
        boolean success = userService.setUserActive(userId, active);
        if (success) {
            auditLogService.record(
                    null, null, "USER", String.valueOf(userId), "USER_STATUS_CHANGED",
                    operator(context), Map.of("userId", userId, "active", active),
                    context.remoteIp(), null);
        }
        return success ? Result.succeed("操作成功") : Result.failure("操作失败");
    }

    @Delete
    @Mapping("/{id}")
    public Result<String> deleteUser(@Path Long id, Context context) {
        User target = userService.getUserById(id);
        boolean success = userService.deleteUser(id);
        if (success) {
            auditLogService.record(
                    null, null, "USER",
                    target == null ? String.valueOf(id) : target.getUsername(),
                    "USER_DELETED", operator(context), Map.of("userId", id),
                    context.remoteIp(), null);
        }
        return success ? Result.succeed("删除成功") : Result.failure("删除失败");
    }

    @Get @Mapping("/{id}/scopes")
    public Result<List<UserScopeRole>> scopes(@Path Long id) { return Result.succeed(authorizationService.scopes(id)); }

    @Post @Mapping("/{id}/scopes")
    public Result<UserScopeRole> grantScope(@Path Long id, @Body UserScopeRole scope, Context context) {
        String operator = operator(context);
        UserScopeRole granted = authorizationService.grant(
                id, scope.getNamespaceId(), scope.getGroupName(), operator);
        auditLogService.record(
                scope.getNamespaceId(), scope.getGroupName(), "USER_SCOPE",
                String.valueOf(id), "USER_SCOPE_GRANTED", operator,
                Map.of("userId", id,
                        "namespaceId", granted.getNamespaceId(),
                        "groupName", granted.getGroupName()),
                context.remoteIp(), null);
        return Result.succeed(granted);
    }

    @Delete @Mapping("/{id}/scopes/{namespaceId}/{groupName}")
    public Result<String> revokeScope(
            @Path Long id,
            @Path String namespaceId,
            @Path String groupName,
            Context context) {
        boolean revoked = authorizationService.revoke(id, namespaceId, groupName);
        if (revoked) {
            auditLogService.record(
                    namespaceId, groupName, "USER_SCOPE", String.valueOf(id),
                    "USER_SCOPE_REVOKED", operator(context), Map.of("userId", id),
                    context.remoteIp(), null);
        }
        return revoked ? Result.succeed("Scope revoked") : Result.failure("Scope not found");
    }

    private String operator(Context context) {
        User current = AdminSecurityContext.currentUser(context);
        return current == null ? "system" : current.getUsername();
    }
}
