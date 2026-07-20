package cloud.xuantong.server.admin.controller;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.ClientAccessToken;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.service.ClientAccessTokenService;
import cloud.xuantong.config.management.service.AuditLogService;
import cloud.xuantong.server.admin.security.AdminSecurityContext;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.*;

@Controller
@Mapping("/api/v2/tokens")
public class ClientAccessTokenController {
    @Inject private ClientAccessTokenService tokenService;
    @Inject private AuditLogService auditLogService;

    @Get @Mapping
    public Result<PageResult<Map<String, Object>>> findAll(
            @Param(defaultValue = "") String keyword,
            @Param(required = false) Boolean active,
            @Param(defaultValue = "1") int page,
            @Param(defaultValue = "20") int pageSize) {
        return Result.succeed(tokenService.findPage(
                keyword, active, new PageQuery(page, pageSize)).map(this::view));
    }

    @Post @Mapping
    public Result<Map<String, Object>> issue(@Body IssueRequest request, Context context) {
        try {
            User user = AdminSecurityContext.currentUser(context);
            Date expiresAt = request.expiresAt == null ? null : new Date(request.expiresAt);
            ClientAccessTokenService.IssuedToken issued = tokenService.issue(
                    request.tokenName, request.tenant, request.namespaceId,
                    request.groupName, expiresAt,
                    user == null ? "system" : user.getUsername());
            Map<String, Object> result = view(issued.token());
            result.put("accessToken", issued.rawToken());
            audit("TOKEN_ISSUED", issued.token().getTokenName(), user,
                    Map.of("tenant", issued.token().getTenant(),
                            "namespaceId", issued.token().getNamespaceId(),
                            "groupName", issued.token().getGroupName()), context);
            return Result.succeed(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete @Mapping("/{id}")
    public Result<String> revoke(@Path Long id, Context context) {
        boolean revoked = tokenService.revoke(id);
        if (revoked) audit("TOKEN_REVOKED", String.valueOf(id),
                AdminSecurityContext.currentUser(context), Map.of("tokenId", id), context);
        return revoked ? Result.succeed("Token revoked") : Result.failure("Token does not exist");
    }

    private Map<String, Object> view(ClientAccessToken token) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", token.getId()); view.put("tokenName", token.getTokenName());
        view.put("tenant", token.getTenant());
        view.put("namespaceId", token.getNamespaceId()); view.put("groupName", token.getGroupName());
        view.put("isActive", token.getIsActive()); view.put("createdBy", token.getCreatedBy());
        view.put("createdAt", token.getCreatedAt()); view.put("expiresAt", token.getExpiresAt());
        return view;
    }

    private void audit(
            String operation,
            String resourceName,
            User user,
            Object detail,
            Context context) {
        auditLogService.record(
                null,
                null,
                "CREDENTIAL",
                resourceName,
                operation,
                user == null ? "system" : user.getUsername(),
                detail,
                context.remoteIp(),
                null);
    }

    public static class IssueRequest {
        public String tokenName;
        public String tenant;
        public String namespaceId;
        public String groupName;
        public Long expiresAt;
    }
}
