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
 * API 鉴权拦截器
 * <p>
 * 对 /api/** 路径检查登录状态，公开接口放行
 */
@Component
public class AuthInterceptor implements RouterInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    @Override
    public PathRule pathPatterns() {
        // 拦截 /api/** 路径
        return new PathRule().include("/api/**");
    }

    @Override
    public void doIntercept(Context ctx, Handler mainHandler, RouterInterceptorChain chain) throws Throwable {
        String path = ctx.pathNew();

        // 公开接口放行
        if (path.startsWith("/api/auth/") || path.startsWith("/health")) {
            chain.doIntercept(ctx, mainHandler);
            return;
        }

        // 检查登录状态
        if (ctx.session("user") == null) {
            log.warn("Unauthorized access attempt: {}", path);
            ctx.status(401);
            ctx.output("{\"code\":401,\"message\":\"unauthorized\"}");
            return;
        }

        chain.doIntercept(ctx, mainHandler);
    }
}
