package com.xuantong.core.model;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityFileProxy;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.xuantong.core.model.proxy.EnvironmentProxy;
import lombok.Data;

/**
 * 环境实体
 */
@Data
@EntityProxy
@EntityFileProxy
@Table("environment")
public class Environment implements ProxyEntityAvailable<Environment , EnvironmentProxy> {
    @Column(primaryKey = true)
    private String code;        /** 环境代码：dev/test/prod等 */

    @Column
    private String name;        /** 环境名称 */

    @Column
    private String description; /** 环境描述 */

    @Column
    private Integer order;      /** 显示顺序 */

    @Column
    private Boolean isDefault;  /** 是否默认环境 */
}