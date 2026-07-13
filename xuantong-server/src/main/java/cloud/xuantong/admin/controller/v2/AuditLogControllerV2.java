package cloud.xuantong.admin.controller.v2;

import cloud.xuantong.core.v2.model.AuditLog;
import cloud.xuantong.core.v2.repository.AuditLogRepository;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/v2/audits")
public class AuditLogControllerV2 {
    @Inject
    private AuditLogRepository auditLogRepository;

    @Get
    @Mapping
    public Result<List<AuditLog>> recent(@Param(defaultValue = "200") int limit) {
        return Result.succeed(auditLogRepository.findRecent(limit));
    }
}
