package cloud.xuantong.admin.interceptor;

import cloud.xuantong.core.model.User;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.route.PathRule;
import org.noear.solon.core.route.RouterInterceptor;
import org.noear.solon.core.route.RouterInterceptorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 全局鉴权拦截器 — 含 RBAC 权限控制
 * <p>
 * 页面路由（/dashboard, /config 等）→ 未登录重定向到 /login
 * API 接口（/api/**）→ 未登录返回 401 JSON
 * 公开路径（/login, /health, /assets, /lib）放行
 * 敏感操作仅 admin 角色可执行
 */
@Component
public class AuthInterceptor implements RouterInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 仅 admin 可访问的 API 路径前缀 */
    private static final Set<String> ADMIN_API_PREFIXES = new HashSet<>(Arrays.asList(
            "/api/user",          // 用户管理
            "/api/env/default",   // 设置默认环境
            "/api/broker"         // Broker 监控
    ));

    /** 仅 admin 可访问的页面路径 */
    private static final Set<String> ADMIN_PAGE_PREFIXES = new HashSet<>(Arrays.asList(
            "/user",              // 用户管理页面
            "/broker"             // Broker 监控页面
    ));

    @Override
    public PathRule pathPatterns() {
        return new PathRule().include("/**");
    }

    @Override
    public void doIntercept(Context ctx, Handler mainHandler, RouterInterceptorChain chain) throws Throwable {
        String path = ctx.pathNew();

        // 公开路径放行
        if (path.equals("/login") || path.startsWith("/api/auth/")
                || path.equals("/health") || path.startsWith("/assets/")
                || path.startsWith("/lib/") || path.startsWith("/.well-known/")) {
            chain.doIntercept(ctx, mainHandler);
            return;
        }

        // CSRF 防护：对写操作检查 Origin/Referer（SameSite 兜底之外的第二层防护）
        if (path.startsWith("/api/") && !"GET".equalsIgnoreCase(ctx.method())) {
            if (!isSameOrigin(ctx)) {
                log.warn("CSRF check failed: {} {}", ctx.method(), path);
                ctx.status(403);
                ctx.output("{\"code\":403,\"message\":\"跨站请求伪造检测\"}");
                return;
            }
        }

        // 检查登录状态
        if (ctx.session("user") == null) {
            log.warn("Unauthorized access: {}", path);

            if (path.startsWith("/api/")) {
                ctx.status(401);
                ctx.output("{\"code\":401,\"message\":\"unauthorized\"}");
            } else {
                ctx.redirect("/login");
            }
            return;
        }

        // RBAC 权限检查
        User user = ctx.session("user", User.class);
        // 防御性检查：session 中存在 "user" 属性但无法解析为 User 类型，视为会话损坏
        if (user == null) {
            log.warn("Session corrupted (user attribute exists but not User type): {}", path);
            ctx.sessionRemove("user");
            if (path.startsWith("/api/")) {
                ctx.status(401);
                ctx.output("{\"code\":401,\"message\":\"session expired\"}");
            } else {
                ctx.redirect("/login");
            }
            return;
        }

        if (!"admin".equals(user.getRole())) {
            // 检查 API 路径
            if (path.startsWith("/api/")) {
                for (String prefix : ADMIN_API_PREFIXES) {
                    if (path.startsWith(prefix)) {
                        log.warn("Forbidden (non-admin): {} by user {}", path, user.getUsername());
                        ctx.status(403);
                        ctx.output("{\"code\":403,\"message\":\"权限不足，仅管理员可操作\"}");
                        return;
                    }
                }
                // 对 /api/project、/api/env、/api/config 的写操作也做限制
                if ((path.startsWith("/api/project") || path.startsWith("/api/env") || path.startsWith("/api/config"))
                        && !"GET".equalsIgnoreCase(ctx.method())) {
                    log.warn("Forbidden (non-admin): {} by user {}", path, user.getUsername());
                    ctx.status(403);
                    ctx.output("{\"code\":403,\"message\":\"权限不足，仅管理员可操作\"}");
                    return;
                }
            }

            // 检查页面路径
            for (String prefix : ADMIN_PAGE_PREFIXES) {
                if (path.startsWith(prefix)) {
                    log.warn("Forbidden page (non-admin): {} by user {}", path, user.getUsername());
                    ctx.status(403);
                    ctx.output("权限不足，仅管理员可访问此页面");
                    return;
                }
            }
        }

        chain.doIntercept(ctx, mainHandler);
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
            return origin.endsWith(host) || origin.contains("://" + host);
        }

        // 降级检查 Referer
        String referer = ctx.header("Referer");
        if (referer != null) {
            return referer.contains("://" + host + "/");
        }

        // 无 Origin 也无 Referer：非浏览器请求（如 curl）放行
        return true;
    }
}
