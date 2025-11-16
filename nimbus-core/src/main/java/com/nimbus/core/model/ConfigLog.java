package com.nimbus.core.model;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityFileProxy;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.nimbus.core.model.proxy.ConfigLogProxy;
import lombok.Data;

import java.util.Date;

/**
 * 配置变更日志
 */
@Data
@EntityProxy
@EntityFileProxy
@Table("config_log")
public class ConfigLog implements ProxyEntityAvailable<ConfigLog , ConfigLogProxy> {
    @Column(primaryKey = true)
    private Long id;

    @Column
    private Long configId;     /** 配置ID */

    @Column
    private String operation;  /** 操作类型：CREATE/UPDATE/DELETE */

    @Column
    private String oldValue;   /** 旧值 */

    @Column
    private String newValue;   /** 新值 */

    @Column
    private String operator;   /** 操作人 */

    @Column
    private Date operateTime;  /** 操作时间 */

    @Column
    private String ipAddress;  /** 操作IP */
}