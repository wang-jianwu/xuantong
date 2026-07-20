package cloud.xuantong.server.admin.controller;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.repository.AuditLogFilter;
import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.config.management.service.AuditLogService.AuditLogView;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Result;

@Controller
@Mapping("/api/v2/audits")
public class AuditLogController {
    @Inject
    private AuditLogService auditLogService;

    @Get
    @Mapping
    public Result<PageResult<AuditLogView>> recent(
            @Param(defaultValue = "") String resourceType,
            @Param(defaultValue = "") String operation,
            @Param(defaultValue = "") String keyword,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "50") int pageSize) {
        return Result.succeed(auditLogService.findPage(
                AuditLogFilter.recent(resourceType, operation, keyword),
                new PageQuery(page, pageSize)));
    }
}
