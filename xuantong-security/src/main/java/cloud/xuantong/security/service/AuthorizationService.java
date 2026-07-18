package cloud.xuantong.security.service;
import cloud.xuantong.security.model.*;
import cloud.xuantong.security.repository.UserScopeRoleRepository;
import org.noear.solon.annotation.*;
import java.util.*;
@Component
public class AuthorizationService {
    @Inject private UserScopeRoleRepository repository;
    public boolean isSystemAdmin(User user) { return user != null && ControlPlaneRole.SYSTEM_ADMIN.name().equals(user.getRole()); }
    public boolean authorize(User user,String namespaceId,String groupName,boolean write,boolean namespaceManagement) {
        if (isSystemAdmin(user)) return true;
        if (user == null || user.getId() == null) return false;
        ControlPlaneRole role;
        try { role=ControlPlaneRole.valueOf(user.getRole()); } catch(Exception e) { return false; }
        if (write && !role.canWrite()) return false;
        if (namespaceManagement && !role.managesNamespace()) return false;
        return repository.findByUserId(user.getId()).stream().anyMatch(s ->
                ("*".equals(s.getNamespaceId()) || s.getNamespaceId().equals(namespaceId)) &&
                (groupName == null || "*".equals(s.getGroupName()) || s.getGroupName().equals(groupName)));
    }
    public List<UserScopeRole> scopes(Long userId) { return repository.findByUserId(userId); }
    public Set<String> authorizedNamespaces(User user) {
        if (isSystemAdmin(user)) return Set.of("*");
        if (user == null || user.getId() == null) return Set.of();
        return repository.findByUserId(user.getId()).stream().map(UserScopeRole::getNamespaceId).collect(java.util.stream.Collectors.toSet());
    }
    public UserScopeRole grant(Long userId,String namespaceId,String groupName,String operator) {
        if(userId==null||namespaceId==null||namespaceId.isBlank()) throw new IllegalArgumentException("userId and namespaceId are required");
        UserScopeRole s=new UserScopeRole(); s.setUserId(userId); s.setNamespaceId(namespaceId.trim()); s.setGroupName(groupName==null||groupName.isBlank()?"*":groupName.trim()); s.setCreatedBy(operator); s.setCreatedAt(new Date()); repository.save(s); return s;
    }
    public boolean revoke(Long userId,String namespaceId,String groupName) { return repository.delete(userId,namespaceId,groupName)==1; }
}
