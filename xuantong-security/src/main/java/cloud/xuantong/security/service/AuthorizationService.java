package cloud.xuantong.security.service;

import cloud.xuantong.security.model.ControlPlaneRole;
import cloud.xuantong.security.model.User;
import cloud.xuantong.security.model.UserScopeRole;
import cloud.xuantong.security.repository.UserScopeRoleRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuthorizationService {
    @Inject
    private UserScopeRoleRepository repository;
    @Inject
    private UserService userService;

    public boolean isSystemAdmin(User user) {
        return user != null && ControlPlaneRole.SYSTEM_ADMIN.name().equals(user.getRole());
    }

    public boolean authorize(User user, String namespaceId, String groupName,
                             boolean write, boolean namespaceManagement) {
        if (isSystemAdmin(user)) {
            return true;
        }
        if (user == null || user.getId() == null) {
            return false;
        }
        ControlPlaneRole role;
        try {
            role = ControlPlaneRole.valueOf(user.getRole());
        } catch (Exception e) {
            return false;
        }
        if (write && !role.canWrite()) {
            return false;
        }
        if (namespaceManagement && !role.managesNamespace()) {
            return false;
        }
        return repository.findByUserId(user.getId()).stream().anyMatch(s ->
                ("*".equals(s.getNamespaceId()) || s.getNamespaceId().equals(namespaceId))
                        && (groupName == null || "*".equals(s.getGroupName())
                        || s.getGroupName().equals(groupName)));
    }

    public List<UserScopeRole> scopes(Long userId) {
        return repository.findByUserId(userId);
    }

    public Set<String> authorizedNamespaces(User user) {
        if (isSystemAdmin(user)) {
            return Set.of("*");
        }
        if (user == null || user.getId() == null) {
            return Set.of();
        }
        return repository.findByUserId(user.getId()).stream()
                .map(UserScopeRole::getNamespaceId)
                .collect(Collectors.toSet());
    }

    public UserScopeRole grant(Long userId, String namespaceId, String groupName,
                               String operator) {
        if (userId == null || namespaceId == null || namespaceId.isBlank()) {
            throw new IllegalArgumentException("userId and namespaceId are required");
        }
        UserScopeRole scope = new UserScopeRole();
        scope.setUserId(userId);
        scope.setNamespaceId(namespaceId.trim());
        scope.setGroupName(groupName == null || groupName.isBlank() ? "*" : groupName.trim());
        scope.setCreatedBy(operator);
        scope.setCreatedAt(new Date());
        repository.save(scope);
        userService.invalidateSessions(userId);
        return scope;
    }

    public boolean revoke(Long userId, String namespaceId, String groupName) {
        boolean revoked = repository.delete(userId, namespaceId, groupName) == 1;
        if (revoked) {
            userService.invalidateSessions(userId);
        }
        return revoked;
    }
}
