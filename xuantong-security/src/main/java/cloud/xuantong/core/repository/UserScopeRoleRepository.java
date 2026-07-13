package cloud.xuantong.core.repository;
import cloud.xuantong.core.model.UserScopeRole;
import java.util.List;
public interface UserScopeRoleRepository {
    List<UserScopeRole> findByUserId(Long userId);
    long save(UserScopeRole scope);
    long delete(Long userId, String namespaceId, String groupName);
}
