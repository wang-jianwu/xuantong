package cloud.xuantong.security.model;
import cloud.xuantong.security.model.proxy.UserScopeRoleProxy;
import com.easy.query.core.annotation.*;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;
import java.util.Date;
@Data @EntityProxy @Table("user_scope_role")
public class UserScopeRole implements ProxyEntityAvailable<UserScopeRole, UserScopeRoleProxy> {
    @Column(primaryKey=true, generatedKey=true) private Long id;
    @Column private Long userId;
    @Column private String namespaceId;
    @Column private String groupName;
    @Column private String createdBy;
    @Column private Date createdAt;
}
