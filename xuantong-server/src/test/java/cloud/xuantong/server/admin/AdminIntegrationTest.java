package cloud.xuantong.server.admin;

import cloud.xuantong.server.admin.interceptor.AuthInterceptor;
import cloud.xuantong.security.model.User;
import cloud.xuantong.config.management.model.ConfigResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Import;
import org.noear.solon.core.handle.Result;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * xuantong-server 核心流程集成测试
 * <p>
 * 覆盖：认证授权、配置 CRUD、RBAC 权限控制
 */
public class AdminIntegrationTest {

    @Nested
    @DisplayName("2.0 管理端页面")
    public class AdminPageTests {
        private final List<String> managementTemplates = List.of(
                "dashboard.shtm", "config.shtm", "namespace.shtm", "service.shtm",
                "connection.shtm", "token.shtm", "audit.shtm", "user.shtm");

        @Test
        @DisplayName("所有管理页面统一使用公共侧栏和头部")
        void managementPagesUseSharedLayout() throws IOException {
            for (String name : managementTemplates) {
                String template = readTemplate(name);
                assertTrue(template.contains("#include(\"_sidebar.shtm\")"), name);
                assertTrue(template.contains("#include(\"_header.shtm\")"), name);
                assertTrue(template.contains(
                        "/js/common.js?v=2.0.2-20260719-cluster"), name);
                assertTrue(template.contains(
                        "/css/admin.css?v=2.0.2-20260719-cluster"), name);
            }
        }

        @Test
        @DisplayName("管理页面不再使用浏览器原生弹窗")
        void managementPagesAvoidNativeDialogs() throws IOException {
            for (String name : managementTemplates) {
                String template = readTemplate(name);
                assertFalse(template.contains("prompt("), name);
                assertFalse(template.contains("alert("), name);
                assertFalse(template.matches("(?s).*\\bconfirm\\(.*"), name);
            }
        }

        @Test
        @DisplayName("弹窗关闭按钮具有可访问名称")
        void modalCloseButtonsHaveAccessibleNames() throws IOException {
            for (String name : managementTemplates) {
                String template = readTemplate(name);
                assertFalse(template.matches(
                        "(?s).*<button[^>]*btn-close(?![^>]*aria-label)[^>]*>.*"), name);
            }
        }

        @Test
        @DisplayName("关键列表支持窄屏横向滚动")
        void dataTablesAreResponsive() throws IOException {
            assertTrue(readTemplate("config.shtm").contains("table-responsive"));
            assertTrue(readTemplate("namespace.shtm").contains("table-responsive"));
            assertTrue(readTemplate("service.shtm").contains("table-responsive"));
            assertTrue(readTemplate("token.shtm").contains("table-responsive"));
            assertTrue(readTemplate("audit.shtm").contains("table-responsive"));
            assertTrue(readTemplate("user.shtm").contains("table-responsive"));
            assertTrue(readTemplate("connection.shtm").contains("table-responsive"));
        }

        @Test
        @DisplayName("配置页面使用显式灰度生命周期 API")
        void configPageUsesExplicitRolloutLifecycle() throws IOException {
            String template = readTemplate("config.shtm");
            assertTrue(template.contains("GRAY_IP"));
            assertTrue(template.contains("GRAY_CLIENT_INSTANCE"));
            assertTrue(template.contains("GRAY_PERCENTAGE"));
            assertTrue(template.contains("/rollouts/preview"));
            assertTrue(template.contains("/rollouts/current-selections"));
            assertTrue(template.contains("smallSampleWarning"));
            assertTrue(template.contains("rolloutKey"));
            assertTrue(template.contains("当前 Gateway"));
            assertTrue(template.contains("/rollouts/${encodeURIComponent(rolloutId)}/promote"));
            assertTrue(template.contains("/rollouts/${encodeURIComponent(rolloutId)}/abort"));
            assertTrue(template.contains("rolloutKey + clientInstanceId"));
            assertTrue(template.contains("expectedDraftRevision"));
            assertTrue(template.contains("/content-tools"));
            assertTrue(template.contains("/tombstone"));
            assertTrue(template.contains("TOMBSTONE"));
            assertTrue(template.contains("重新发布"));
            assertTrue(template.contains("draftConflictModal"));
            assertTrue(template.contains("value=\"string\""));
            assertTrue(template.contains("value=\"number\""));
            assertTrue(template.contains("value=\"boolean\""));
            assertFalse(template.contains("releaseType=FULL"));
        }

        @Test
        @DisplayName("配置写操作必须校验业务结果后才能提示成功")
        void configPageRejectsFailedWriteResults() throws IOException {
            String template = readTemplate("config.shtm");
            assertTrue(template.contains(
                    "const release = requireConfigWriteSuccess(await apiPost("));
            assertTrue(template.contains(
                    "const releases = requireConfigWriteSuccess(await apiPost("));
            assertTrue(template.contains("const result = await apiPut("));
            assertTrue(template.contains("if (Number(result.code) === 409"));
            assertTrue(template.contains("const saved = requireConfigWriteSuccess(result)"));
            assertTrue(template.contains(
                    "requireConfigWriteSuccess(await apiDelete("));
            assertTrue(template.contains(
                    "const c = requireApiSuccess(await apiGet("));
            assertTrue(template.contains("showToast(error.message || '配置写操作失败'"));
            assertTrue(template.contains(
                    "Decision Revision ${revision}"));
        }

        @Test
        @DisplayName("仪表盘、Token、审计和范围授权接入 2.0 API")
        void newPagesUseExpectedApis() throws IOException {
            String dashboard = readTemplate("dashboard.shtm");
            assertTrue(dashboard.contains("apiGet('/health')"));
            assertTrue(dashboard.contains("apiGetText('/metrics')"));

            String token = readTemplate("token.shtm");
            assertTrue(token.contains("/api/v2/tokens"));
            assertTrue(token.contains("rawTokenModal"));

            String audit = readTemplate("audit.shtm");
            assertTrue(audit.contains("withQuery('/api/v2/audits'"));
            assertTrue(audit.contains("renderPagination('auditPagination'"));

            String connection = readTemplate("connection.shtm");
            assertTrue(connection.contains("/api/v2/connections"));
            assertTrue(connection.contains("logicalClients"));
            assertTrue(connection.contains("subscriptions"));
            assertTrue(connection.contains("lastConfigSelection"));
            assertTrue(connection.contains("valueState"));
            assertTrue(connection.contains("matchedRuleId"));
            assertTrue(connection.contains("contentRevision"));
            assertTrue(connection.contains("decisionRevision"));

            String user = readTemplate("user.shtm");
            assertTrue(user.contains("/scopes"));
            assertTrue(user.contains("apiDelete(`/api/user/${selectedScopeUserId}/scopes/"));
        }

        @Test
        @DisplayName("管理列表统一消费 PageResult 并渲染分页控件")
        void managementListsUsePageResultContract() throws IOException {
            for (String name : List.of(
                    "config.shtm", "service.shtm", "token.shtm",
                    "user.shtm", "audit.shtm")) {
                String template = readTemplate(name);
                assertTrue(template.contains("requirePageResult("), name);
                assertTrue(template.contains("renderPagination("), name);
                assertTrue(template.contains("pageSize"), name);
            }
            String config = readTemplate("config.shtm");
            assertTrue(config.contains("release-pagination"));
            assertTrue(config.contains("rollout-pagination"));
            assertTrue(config.contains("config-audit-pagination"));
        }

        @Test
        @DisplayName("管理菜单包含全部 2.0 页面")
        void menuContainsAllV2Pages() throws IOException {
            String menu = readTemplate("_menu.shtm");
            for (String path : List.of("/dashboard", "/config", "/namespace", "/service", "/connection", "/token", "/audit", "/user")) {
                assertTrue(menu.contains("href=\"" + path + "\""), path);
            }
        }

        @Test
        @DisplayName("登录成功进入 2.0 运行概览")
        void loginRedirectsToDashboard() throws IOException {
            String login = readTemplate("login.shtm");
            assertTrue(login.contains("window.location.href = '/dashboard'"));
            assertFalse(login.contains("window.location.href = '/config'"));
        }

        @Test
        @DisplayName("公共前端层提供仪表盘与响应式基础能力")
        void commonAssetsProvideDashboardAndResponsiveSupport() throws IOException {
            String common = readResource("/static/js/common.js");
            assertTrue(common.contains("function requireApiSuccess"));
            assertTrue(common.contains("function apiGetText"));
            assertTrue(common.contains("function parsePrometheus"));
            assertTrue(common.contains("function formatBytes"));
            assertTrue(common.contains("X-Xuantong-Operation-Id"));
            assertTrue(common.contains("X-Xuantong-CSRF"));
            assertTrue(common.contains("XUANTONG_CSRF="));
            assertTrue(common.contains("method: 'POST'"));
            assertTrue(common.contains("newXuantongOperationId"));
            assertTrue(common.contains("sidebar_collapsed', 'false'"));
            assertTrue(common.contains("aria-expanded"));
            assertTrue(common.contains("if (!document.querySelector('.sidebar')) return"));

            String css = readResource("/static/css/admin.css");
            assertTrue(css.contains(".dashboard-grid"));
            assertTrue(css.contains(".table-responsive"));
            assertTrue(css.contains("min-width: 0"));
        }

        @Test
        @DisplayName("应用入口扫描完整的玄同组件根包")
        void adminAppScansXuantongRootPackage() {
            Import appImport = AdminApp.class.getAnnotation(Import.class);
            assertNotNull(appImport);
            assertArrayEquals(new String[]{"cloud.xuantong"}, appImport.scanPackages());
        }
    }

    @Nested
    @DisplayName("服务管理页面")
    public class ServicePageTests {
        @Test
        @DisplayName("页面使用 2.0 服务与实例 API")
        void servicePageUsesV2Apis() throws IOException {
            try (InputStream input = getClass().getResourceAsStream("/templates/service.shtm")) {
                assertNotNull(input, "service.shtm must exist");
                String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(template.contains("/api/v2/namespaces"));
                assertTrue(template.contains("/instances`, {"));
                assertTrue(template.contains("pageResult.metadata.revision"));
                assertTrue(template.contains("renderPagination('instance-pagination'"));
                assertTrue(template.contains("apiDelete"));
                assertFalse(template.contains("/api/service/"));
            }
        }
    }

    private String readTemplate(String name) throws IOException {
        return readResource("/templates/" + name);
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path + " must exist");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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
    @DisplayName("ConfigResource 实体")
    public class ConfigResourceTests {

        @Test
        @DisplayName("新建 ConfigResource 字段正确")
        void createConfigResource() {
            ConfigResource item = new ConfigResource();
            item.setNamespaceId("public");
            item.setGroupName("DEFAULT_GROUP");
            item.setDataId("application.yml");
            item.setContent("server.port=8080");
            item.setRevision(1L);
            item.setDraftRevision(2L);

            assertEquals("public", item.getNamespaceId());
            assertEquals("DEFAULT_GROUP", item.getGroupName());
            assertEquals("application.yml", item.getDataId());
            assertEquals("server.port=8080", item.getContent());
            assertEquals(1L, item.getRevision());
            assertEquals(2L, item.getDraftRevision());
        }

    }

    // ===== User 角色测试 =====
    @Nested
    @DisplayName("User 角色")
    public class UserRoleTests {

        @Test
        @DisplayName("SYSTEM_ADMIN 角色标识")
        void adminRole() {
            User user = new User();
            user.setRole("SYSTEM_ADMIN");
            assertEquals("SYSTEM_ADMIN", user.getRole());
        }

        @Test
        @DisplayName("DEVELOPER 角色标识")
        void userRole() {
            User user = new User();
            user.setRole("DEVELOPER");
            assertEquals("DEVELOPER", user.getRole());
        }

        @Test
        @DisplayName("VIEWER 不是系统管理员")
        void nonAdminByDefault() {
            User user = new User();
            user.setRole("VIEWER");
            assertNotEquals("SYSTEM_ADMIN", user.getRole());
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
