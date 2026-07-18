package cloud.xuantong.security.repository;
import cloud.xuantong.security.model.UserScopeRole;
import java.util.List;
public interface UserScopeRoleRepository {
    List<UserScopeRole> findByUserId(Long userId);
    long save(UserScopeRole scope);
    long delete(Long userId, String namespaceId, String groupName);
}
