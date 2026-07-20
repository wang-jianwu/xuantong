package cloud.xuantong.server.admin.interceptor;

import cloud.xuantong.security.model.User;
import cloud.xuantong.security.service.AuthorizationService;
import cloud.xuantong.server.admin.security.AdminSessionService;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.route.PathRule;
import org.noear.solon.core.route.RouterInterceptor;
import org.noear.solon.core.route.RouterInterceptorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局鉴权拦截器 — 含 RBAC 权限控制
 * <p>
 * 页面路由（/config、/namespace 等）→ 未登录重定向到 /login
 * API 接口（/api/**）→ 未登录返回 401 JSON
 * 公开路径（/login, /health, /assets, /lib）放行
 * 敏感操作仅 admin 角色可执行
 */
@Component
public class AuthInterceptor implements RouterInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    private static final Pattern NAMESPACE_PATH = Pattern.compile(
            "^/api/v2/namespaces/([^/]+)(?:/groups/([^/]+))?.*$");
    @Inject private AuthorizationService authorizationService;
    @Inject private AdminSessionService sessionService;

    @Override
    public PathRule pathPatterns() {
        return new PathRule().include("/**");
    }

    @Override
    public void doIntercept(Context ctx, Handler mainHandler, RouterInterceptorChain chain) throws Throwable {
        String path = ctx.pathNew();

        // 登录接口不依赖现有会话，但仍执行同源校验，避免 Login CSRF。
        if (path.equals("/api/auth/login")) {
            if (isUnsafe(ctx.method()) && !isSameOrigin(ctx)) {
                rejectCsrf(ctx, path);
                return;
            }
            chain.doIntercept(ctx, mainHandler);
            return;
        }

        // 完全公开路径放行。登录页会尝试恢复已有签名会话，以便已登录用户直接跳转。
        if (path.equals("/login")) {
            sessionService.authenticate(ctx);
            chain.doIntercept(ctx, mainHandler);
            return;
        }
        if (path.equals("/health") || path.equals("/metrics") || path.startsWith("/assets/")
                || path.startsWith("/lib/") || path.startsWith("/.well-known/")) {
            chain.doIntercept(ctx, mainHandler);
            return;
        }

        // 每次请求验证签名、有效期、用户启用状态和 securityVersion。
        User user = sessionService.authenticate(ctx);
        if (user == null) {
            log.warn("Unauthorized access: {}", path);
            rejectUnauthorized(ctx, path);
            return;
        }

        // 写 API 同时要求同源和绑定当前签名会话的 CSRF Token。
        if (path.startsWith("/api/") && isUnsafe(ctx.method())
                && (!isSameOrigin(ctx) || !sessionService.validateCsrf(ctx))) {
            rejectCsrf(ctx, path);
            return;
        }

        // RBAC 权限检查
        if (!isAuthorized(user, path, ctx.method())) {
            log.warn("Forbidden: {} {} by user {}", ctx.method(), path, user.getUsername());
            ctx.status(403);
            ctx.output(path.startsWith("/api/")
                    ? "{\"code\":403,\"message\":\"权限不足或资源范围未授权\"}"
                    : "权限不足");
            return;
        }

        chain.doIntercept(ctx, mainHandler);
    }

    private boolean isUnsafe(String method) {
        return !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
    }

    private void rejectUnauthorized(Context context, String path) {
        if (path.startsWith("/api/")) {
            context.status(401);
            context.output("{\"code\":401,\"message\":\"unauthorized\"}");
        } else {
            context.redirect("/login");
        }
    }

    private void rejectCsrf(Context context, String path) {
        log.warn("CSRF check failed: {} {}", context.method(), path);
        context.status(403);
        context.output("{\"code\":403,\"message\":\"invalid csrf token\"}");
    }

    boolean isAuthorized(User user, String path, String method) {
        if (authorizationService.isSystemAdmin(user)) return true;
        if (path.startsWith("/api/user") || path.startsWith("/api/v2/tokens")
                || path.startsWith("/api/v2/audits") || path.startsWith("/api/v2/connections")
                || path.startsWith("/user") || path.startsWith("/token")
                || path.startsWith("/audit") || path.startsWith("/connection")) return false;
        if (!path.startsWith("/api/v2/")) return true;
        boolean write = !"GET".equalsIgnoreCase(method);
        if ("/api/v2/namespaces".equals(path)) return !write;
        Matcher matcher = NAMESPACE_PATH.matcher(path);
        if (!matcher.matches()) return false;
        String namespaceId = matcher.group(1);
        String groupName = matcher.group(2);
        boolean namespaceManagement = write && path.matches("^/api/v2/namespaces/[^/]+/groups/?$");
        return authorizationService.authorize(user, namespaceId, groupName, write, namespaceManagement);
    }

    /**
     * 检查请求是否同源（Origin/Referer 与 Host 一致）
     * 用于 CSRF 防护：浏览器自动附加的 Origin/Referer 无法被跨站脚本伪造
     */
    private boolean isSameOrigin(Context ctx) {
        String host = ctx.header("Host");
        if (host == null) return false;

        // 优先检查 Origin
        String origin = ctx.header("Origin");
        if (origin != null) {
            return hasSameAuthority(origin, host);
        }

        // 降级检查 Referer
        String referer = ctx.header("Referer");
        if (referer != null) {
            return hasSameAuthority(referer, host);
        }

        // 无 Origin 也无 Referer：非浏览器请求（如 curl）放行
        return true;
    }

    private boolean hasSameAuthority(String url, String host) {
        try {
            URI uri = URI.create(url);
            return uri.getRawAuthority() != null && uri.getRawAuthority().equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
