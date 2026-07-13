package cloud.xuantong.core;
import cloud.xuantong.core.model.*;
import cloud.xuantong.core.repository.UserScopeRoleRepository;
import cloud.xuantong.core.service.AuthorizationService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AuthorizationServiceTest {
    @Test void enforcesRoleAndResourceScope() throws Exception {
        MemoryScopes scopes=new MemoryScopes(); AuthorizationService service=new AuthorizationService();
        Field f=AuthorizationService.class.getDeclaredField("repository"); f.setAccessible(true); f.set(service,scopes);
        User developer=user(2L,"DEVELOPER"); scopes.save(scope(2L,"prod","PAYMENT"));
        assertTrue(service.authorize(developer,"prod","PAYMENT",true,false));
        assertFalse(service.authorize(developer,"prod","ORDER",false,false));
        User viewer=user(3L,"VIEWER"); scopes.save(scope(3L,"prod","*"));
        assertTrue(service.authorize(viewer,"prod","PAYMENT",false,false));
        assertFalse(service.authorize(viewer,"prod","PAYMENT",true,false));
        assertTrue(service.authorize(user(1L,"SYSTEM_ADMIN"),"any","any",true,true));
    }
    private User user(Long id,String role){User u=new User();u.setId(id);u.setRole(role);return u;}
    private UserScopeRole scope(Long id,String ns,String group){UserScopeRole s=new UserScopeRole();s.setUserId(id);s.setNamespaceId(ns);s.setGroupName(group);return s;}
    static class MemoryScopes implements UserScopeRoleRepository {
        final List<UserScopeRole> items=new ArrayList<>();
        public List<UserScopeRole> findByUserId(Long id){return items.stream().filter(s->s.getUserId().equals(id)).toList();}
        public long save(UserScopeRole s){items.add(s);return 1;}
        public long delete(Long id,String ns,String group){return items.removeIf(s->s.getUserId().equals(id)&&s.getNamespaceId().equals(ns)&&s.getGroupName().equals(group))?1:0;}
    }
}
