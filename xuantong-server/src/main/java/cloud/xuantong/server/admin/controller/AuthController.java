package cloud.xuantong.server.admin.controller;

import cloud.xuantong.security.model.User;
import cloud.xuantong.security.service.UserService;
import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.server.admin.security.AdminLoginGuard;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import cloud.xuantong.server.admin.security.AdminSessionService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.HashMap;
import java.util.Map;

@Controller
@Mapping("/api/auth")
public class AuthController {
    @Inject
    private UserService userService;
    @Inject
    private AdminSessionService sessionService;
    @Inject
    private AdminLoginGuard loginGuard;
    @Inject
    private AuditLogService auditLogService;

    @Post
    @Mapping("/login")
    public Result<Map<String, Object>> login(
            @Body LoginRequest request, Context ctx) {
        String username = request == null || request.username == null
                ? ""
                : request.username.trim();
        String password = request == null ? null : request.password;
        String remoteIp = remoteIp(ctx);
        if (username.isBlank() || username.length() > 50
                || password == null || password.isEmpty() || password.length() > 1_024) {
            ctx.status(401);
            audit("ADMIN_LOGIN_FAILED", username, remoteIp, "invalid credentials");
            return Result.failure("用户名或密码错误");
        }

        AdminLoginGuard.Decision current = loginGuard.check(username, remoteIp);
        if (!current.allowed()) {
            return rateLimited(ctx, username, remoteIp, current);
        }

        User user = userService.authenticate(username, password);
        if (user != null) {
            loginGuard.recordSuccess(username);
            try {
                sessionService.issue(ctx, user);
                audit("ADMIN_LOGIN_SUCCEEDED", username, remoteIp, "signed session issued");
            } catch (RuntimeException e) {
                sessionService.clear(ctx);
                throw e;
            }
            return Result.succeed(toSafeMap(user));
        }

        AdminLoginGuard.Decision failed = loginGuard.recordFailure(username, remoteIp);
        audit("ADMIN_LOGIN_FAILED", username, remoteIp, "invalid credentials");
        if (!failed.allowed()) {
            return rateLimited(ctx, username, remoteIp, failed);
        }
        ctx.status(401);
        return Result.failure("用户名或密码错误");
    }

    @Post
    @Mapping("/logout")
    public Result<String> logout(Context context) {
        User user = AdminSecurityContext.currentUser(context);
        sessionService.clear(context);
        audit("ADMIN_LOGOUT", user == null ? "unknown" : user.getUsername(),
                remoteIp(context), "signed session cleared");
        return Result.succeed("退出成功");
    }

    @Get
    @Mapping("/userinfo")
    public Result<Map<String, Object>> getUserInfo(Context context) {
        User user = AdminSecurityContext.currentUser(context);
        return user != null ? Result.succeed(toSafeMap(user)) : Result.failure("未登录");
    }

    private Result<Map<String, Object>> rateLimited(
            Context context, String username, String remoteIp,
            AdminLoginGuard.Decision decision) {
        context.status(429);
        context.headerSet("Retry-After", Long.toString(decision.retryAfterSeconds()));
        audit("ADMIN_LOGIN_RATE_LIMITED", username, remoteIp,
                "retryAfterSeconds=" + decision.retryAfterSeconds());
        return Result.failure("登录尝试过于频繁，请稍后重试");
    }

    private void audit(String operation, String username, String remoteIp, String detail) {
        String normalizedUsername = username == null || username.isBlank()
                ? "unknown"
                : username;
        auditLogService.record(
                null,
                null,
                "ADMIN_SESSION",
                normalizedUsername,
                operation,
                "unknown".equals(normalizedUsername) ? "anonymous" : normalizedUsername,
                detail,
                remoteIp,
                null);
    }

    private String remoteIp(Context context) {
        // 不直接信任客户端可伪造的转发头；反向代理场景应由受信代理统一传递来源。
        String value = context.remoteIp();
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * 过滤密码哈希，防止 User 序列化时泄露密码字段
     */
    private Map<String, Object> toSafeMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("email", user.getEmail());
        map.put("realName", user.getRealName());
        map.put("role", user.getRole());
        map.put("isActive", user.getIsActive());
        map.put("lastLoginTime", user.getLastLoginTime());
        return map;
    }

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
