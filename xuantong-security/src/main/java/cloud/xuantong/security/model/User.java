package cloud.xuantong.security.model;

import cloud.xuantong.security.model.proxy.UserProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

/**
 * 用户实体
 */
@Data
@EntityProxy
@Table("user")
public class User implements ProxyEntityAvailable<User , UserProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;

    @Column

    private String username;      /** 用户名 */

    @Column
    private String password;      /** 密码（加密） */

    @Column
    private String email;         /** 邮箱 */

    @Column
    private String realName;      /** 真实姓名 */

    @Column
    private String role;          /** SYSTEM_ADMIN/NAMESPACE_ADMIN/DEVELOPER/VIEWER */

    @Column
    private Boolean isActive;     /** 是否激活 */

    /**
     * 管理会话安全版本。密码、角色、启停状态或授权范围发生变化时递增，
     * 用于让所有 Server 上已经签发的旧会话立即失效。
     */
    @Column
    private Long securityVersion;

    @Column
    private Date createdAt;      /** 创建时间 */

    @Column
    private Date lastLoginTime;   /** 最后登录时间 */
}
