package com.nimbus.core.model;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityFileProxy;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.nimbus.core.model.proxy.UserProxy;
import lombok.Data;

import java.util.Date;

/**
 * 用户实体
 */
@Data
@EntityProxy
@EntityFileProxy
@Table("user")
public class User implements ProxyEntityAvailable<User , UserProxy> {
    @Column(primaryKey = true)
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
    private String role;          /** 角色：admin/user/guest */

    @Column
    private Boolean isActive;     /** 是否激活 */

    @Column
    private Date createdAt;      /** 创建时间 */

    @Column
    private Date lastLoginTime;   /** 最后登录时间 */
}