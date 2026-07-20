package cloud.xuantong.server.admin.interceptor;

import cloud.xuantong.security.model.User;
import cloud.xuantong.security.service.AuthorizationService;
import cloud.xuantong.server.admin.security.AdminSessionService;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.route.RouterInterceptorChain;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorSecurityTest {
    @Test
    void crossOriginWriteIsRejected() throws Throwable {
        TestSessionService sessions = new TestSessionService(true);
        AuthInterceptor interceptor = interceptor(sessions);
        TestContext context = context("POST");
        context.headerMap().put("Host", "xuantong.example.com");
        context.headerMap().put("Origin", "https://evil.example.com");

        AtomicBoolean invoked = invoke(interceptor, context);
        assertFalse(invoked.get());
        assertEquals(403, context.status());
    }

    @Test
    void sameOriginWriteStillRequiresSessionBoundCsrfToken() throws Throwable {
        TestSessionService sessions = new TestSessionService(false);
        AuthInterceptor interceptor = interceptor(sessions);
        TestContext context = context("POST");
        context.headerMap().put("Host", "xuantong.example.com");
        context.headerMap().put("Origin", "https://xuantong.example.com");

        AtomicBoolean invoked = invoke(interceptor, context);
        assertFalse(invoked.get());
        assertEquals(403, context.status());
    }

    @Test
    void sameOriginWriteWithValidCsrfReachesController() throws Throwable {
        AuthInterceptor interceptor = interceptor(new TestSessionService(true));
        TestContext context = context("POST");
        context.headerMap().put("Host", "xuantong.example.com");
        context.headerMap().put("Origin", "https://xuantong.example.com");

        AtomicBoolean invoked = invoke(interceptor, context);
        assertTrue(invoked.get());
    }

    private AuthInterceptor interceptor(AdminSessionService sessions) throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor();
        set(interceptor, "sessionService", sessions);
        set(interceptor, "authorizationService", new AuthorizationService());
        return interceptor;
    }

    private AtomicBoolean invoke(AuthInterceptor interceptor, Context context) throws Throwable {
        AtomicBoolean invoked = new AtomicBoolean();
        Handler handler = ignored -> { };
        RouterInterceptorChain chain = (ignored, mainHandler) -> invoked.set(true);
        interceptor.doIntercept(context, handler, chain);
        return invoked;
    }

    private TestContext context(String method) {
        TestContext context = new TestContext(method);
        context.pathNew("/api/v2/namespaces/public/groups/DEFAULT_GROUP/configs/app.yml");
        return context;
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static User admin() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("SYSTEM_ADMIN");
        user.setIsActive(true);
        user.setSecurityVersion(1L);
        return user;
    }

    private static class TestSessionService extends AdminSessionService {
        private final boolean csrfValid;

        private TestSessionService(boolean csrfValid) {
            this.csrfValid = csrfValid;
        }

        @Override public User authenticate(Context context) { return admin(); }
        @Override public boolean validateCsrf(Context context) { return csrfValid; }
    }

    private static class TestContext extends ContextEmpty {
        private final String method;

        private TestContext(String method) {
            this.method = method;
        }

        @Override public String method() { return method; }
    }
}
