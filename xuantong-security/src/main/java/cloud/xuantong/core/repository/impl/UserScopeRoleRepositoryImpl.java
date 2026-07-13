package cloud.xuantong.core.repository.impl;
import cloud.xuantong.core.model.UserScopeRole;
import cloud.xuantong.core.repository.UserScopeRoleRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;
import java.util.List;
@Component
public class UserScopeRoleRepositoryImpl implements UserScopeRoleRepository {
    @Db private EasyEntityQuery easyQuery;
    public List<UserScopeRole> findByUserId(Long id) { return easyQuery.queryable(UserScopeRole.class).where(o->o.userId().eq(id)).orderBy(o->o.namespaceId().asc()).toList(); }
    public long save(UserScopeRole scope) { return easyQuery.insertable(scope).executeRows(true); }
    public long delete(Long userId,String ns,String group) { return easyQuery.deletable(UserScopeRole.class).allowDeleteStatement(true).where(o->{o.userId().eq(userId);o.namespaceId().eq(ns);o.groupName().eq(group);}).executeRows(); }
}
