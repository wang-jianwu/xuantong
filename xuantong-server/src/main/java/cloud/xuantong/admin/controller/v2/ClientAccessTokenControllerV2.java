package cloud.xuantong.admin.controller.v2;

import cloud.xuantong.core.model.ClientAccessToken;
import cloud.xuantong.core.model.User;
import cloud.xuantong.core.service.ClientAccessTokenService;
import cloud.xuantong.core.v2.model.AuditLog;
import cloud.xuantong.core.v2.repository.AuditLogRepository;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.*;

@Controller
@Mapping("/api/v2/tokens")
public class ClientAccessTokenControllerV2 {
    @Inject private ClientAccessTokenService tokenService;
    @Inject private AuditLogRepository auditLogRepository;

    @Get @Mapping
    public Result<List<Map<String, Object>>> findAll() {
        return Result.succeed(tokenService.findAll().stream().map(this::view).toList());
    }

    @Post @Mapping
    public Result<Map<String, Object>> issue(@Body IssueRequest request, Context context) {
        try {
            User user = context.session("user", User.class);
            Date expiresAt = request.expiresAt == null ? null : new Date(request.expiresAt);
            ClientAccessTokenService.IssuedToken issued = tokenService.issue(
                    request.tokenName, request.namespaceId, request.groupName, expiresAt,
                    user == null ? "system" : user.getUsername());
            Map<String, Object> result = view(issued.token());
            result.put("accessToken", issued.rawToken());
            audit("TOKEN_ISSUED", issued.token().getTokenName(), user, issued.token().getNamespaceId() + "/" + issued.token().getGroupName());
            return Result.succeed(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Delete @Mapping("/{id}")
    public Result<String> revoke(@Path Long id, Context context) {
        boolean revoked = tokenService.revoke(id);
        if (revoked) audit("TOKEN_REVOKED", String.valueOf(id), context.session("user", User.class), "tokenId=" + id);
        return revoked ? Result.succeed("Token revoked") : Result.failure("Token does not exist");
    }

    private Map<String, Object> view(ClientAccessToken token) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", token.getId()); view.put("tokenName", token.getTokenName());
        view.put("namespaceId", token.getNamespaceId()); view.put("groupName", token.getGroupName());
        view.put("isActive", token.getIsActive()); view.put("createdBy", token.getCreatedBy());
        view.put("createdAt", token.getCreatedAt()); view.put("expiresAt", token.getExpiresAt());
        return view;
    }

    private void audit(String operation, String resourceName, User user, String detail) {
        AuditLog log = new AuditLog();
        log.setResourceType("CREDENTIAL"); log.setResourceName(resourceName); log.setOperation(operation);
        log.setOperator(user == null ? "system" : user.getUsername()); log.setDetail(detail); log.setCreatedAt(new Date());
        auditLogRepository.save(log);
    }

    public static class IssueRequest {
        public String tokenName;
        public String namespaceId;
        public String groupName;
        public Long expiresAt;
    }
}
