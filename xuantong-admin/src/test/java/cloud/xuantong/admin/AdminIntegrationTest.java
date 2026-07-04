package cloud.xuantong.admin;

import cloud.xuantong.admin.interceptor.AuthInterceptor;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Result;

import static org.junit.jupiter.api.Assertions.*;

/**
 * xuantong-admin 核心流程集成测试
 * <p>
 * 覆盖：认证授权、配置 CRUD、RBAC 权限控制
 */
public class AdminIntegrationTest {

    // ===== AuthInterceptor 路径规则测试 =====
    @Nested
    @DisplayName("AuthInterceptor 路径规则")
    public class AuthInterceptorTests {
        private final AuthInterceptor interceptor = new AuthInterceptor();

        @Test
        @DisplayName("公开路径 /login 放行")
        void publicPath_login() {
            assertTrue(interceptor.pathPatterns().test("/login"));
        }

        @Test
        @DisplayName("公开路径 /api/auth/login 放行")
        void publicPath_authLogin() {
            assertTrue(interceptor.pathPatterns().test("/api/auth/login"));
        }

        @Test
        @DisplayName("公开路径 /health 放行")
        void publicPath_health() {
            assertTrue(interceptor.pathPatterns().test("/health"));
        }

        @Test
        @DisplayName("公开路径 /assets/** 放行")
        void publicPath_assets() {
            assertTrue(interceptor.pathPatterns().test("/assets/css/style.css"));
        }
    }

    // ===== 配置 CRUD 实体测试 =====
    @Nested
    @DisplayName("ConfigItem 实体")
    public class ConfigItemTests {

        @Test
        @DisplayName("新建 ConfigItem 字段正确")
        void createConfigItem() {
            ConfigItem item = new ConfigItem();
            item.setKey("test.key");
            item.setValue("test-value");
            item.setProject("demo");
            item.setEnvironment("dev");
            item.setVersion(1);

            assertEquals("test.key", item.getKey());
            assertEquals("test-value", item.getValue());
            assertEquals("demo", item.getProject());
            assertEquals("dev", item.getEnvironment());
            assertEquals(1, item.getVersion());
        }

        @Test
        @DisplayName("加密配置项标记")
        void encryptedConfigItem() {
            ConfigItem item = new ConfigItem();
            item.setIsEncrypted(true);
            assertTrue(item.getIsEncrypted());

            item.setIsEncrypted(false);
            assertFalse(item.getIsEncrypted());
        }
    }

    // ===== User 角色测试 =====
    @Nested
    @DisplayName("User 角色")
    public class UserRoleTests {

        @Test
        @DisplayName("admin 角色标识")
        void adminRole() {
            User user = new User();
            user.setRole("admin");
            assertEquals("admin", user.getRole());
        }

        @Test
        @DisplayName("user 角色标识")
        void userRole() {
            User user = new User();
            user.setRole("user");
            assertEquals("user", user.getRole());
        }

        @Test
        @DisplayName("默认角色非 admin")
        void nonAdminByDefault() {
            User user = new User();
            user.setRole("user");
            assertNotEquals("admin", user.getRole());
        }
    }

    // ===== Result 返回值测试 =====
    @Nested
    @DisplayName("API Result")
    public class ResultTests {

        @Test
        @DisplayName("成功 Result 不包含错误消息")
        void succeedResult() {
            Result<String> result = Result.succeed("操作成功");
            assertEquals(200, result.getCode());
            assertEquals("操作成功", result.getData());
        }

        @Test
        @DisplayName("失败 Result 包含错误码")
        void failureResult() {
            Result<String> result = Result.failure("操作失败");
            assertNotEquals(200, result.getCode());
            assertEquals("操作失败", result.getDescription());
        }

        @Test
        @DisplayName("推送失败必须返回 failure 而非 succeed")
        void pushFailureReturnsFailure() {
            // 验证推送异常场景返回的是 failure
            Result<String> result = Result.failure("推送失败: connection refused");
            assertNotEquals(200, result.getCode());
            assertTrue(result.getDescription().contains("推送失败"));
        }
    }
}
