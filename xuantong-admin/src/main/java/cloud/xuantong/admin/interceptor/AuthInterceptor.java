package cloud.xuantong.admin.interceptor;

import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.route.PathRule;
import org.noear.solon.core.route.RouterInterceptor;
import org.noear.solon.core.route.RouterInterceptorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局鉴权拦截器
 * <p>
 * 页面路由（/dashboard, /config 等）→ 未登录重定向到 /login
 * API 接口（/api/**）→ 未登录返回 401 JSON
 * 公开路径（/login, /health, /assets, /lib）放行
 */
@Component
public class AuthInterceptor implements RouterInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    @Override
    public PathRule pathPatterns() {
        // 拦截所有路径
        return new PathRule().include("/**");
    }

    @Override
    public void doIntercept(Context ctx, Handler mainHandler, RouterInterceptorChain chain) throws Throwable {
        String path = ctx.pathNew();

        // 公开路径放行
        if (path.equals("/login") || path.startsWith("/api/auth/")
                || path.equals("/health") || path.startsWith("/assets/")
                || path.startsWith("/lib/")) {
            chain.doIntercept(ctx, mainHandler);
            return;
        }

        // 检查登录状态
        if (ctx.session("user") == null) {
            log.warn("Unauthorized access: {}", path);

            if (path.startsWith("/api/")) {
                // API 接口返回 401
                ctx.status(401);
                ctx.output("{\"code\":401,\"message\":\"unauthorized\"}");
            } else {
                // 页面路由重定向到登录
                ctx.redirect("/login");
            }
            return;
        }

        chain.doIntercept(ctx, mainHandler);
    }
}
