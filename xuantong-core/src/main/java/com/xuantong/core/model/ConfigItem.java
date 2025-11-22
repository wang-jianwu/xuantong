package com.xuantong.core.model;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityFileProxy;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.xuantong.core.model.proxy.ConfigItemProxy;
import lombok.Data;

import java.util.Date;

/**
 * 配置项实体
 */
@Data
@EntityProxy
@EntityFileProxy
@Table("config_item")
public class ConfigItem implements ProxyEntityAvailable<ConfigItem , ConfigItemProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;

    @Column
    private String key;          /** 配置键 */

    @Column
    private String value;        /** 配置值 */

    @Column
    private String description;  /** 配置描述 */

    @Column
    private String environment;  /** 环境：dev/test/prod */

    @Column
    private String project;      /** 所属项目 */

    @Column
    private Integer version;     /** 版本号 */

    @Column
    private String createdBy;     /** 创建人 */

    @Column
    private Date createdAt;      /** 创建时间 */

    @Column
    private Date updatedAt;      /** 更新时间 */

    @Column
    private Boolean isEncrypted;  /* 是否加密存储 */
}