package cloud.xuantong.core.model;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityFileProxy;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import cloud.xuantong.core.model.proxy.ProjectProxy;
import lombok.Data;

import java.util.Date;

/**
 * 项目实体
 */
@Data
@EntityProxy
@EntityFileProxy
@Table("project")
public class Project implements ProxyEntityAvailable<Project , ProjectProxy> {
    @Column(primaryKey = true)
    private Long id;

    @Column
    private String code;          /** 项目代码 */

    @Column
    private String name;          /** 项目名称 */

    @Column
    private String description;   /** 项目描述 */

    @Column
    private String owner;         /** 项目负责人 */

    @Column
    private Boolean isActive;     /** 是否激活 */

    @Column
    private Date createdAt;      /** 创建时间 */

    @Column
    private String createdBy;     /** 创建人 */
}