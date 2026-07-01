package cloud.xuantong.core.model;

import cloud.xuantong.core.model.proxy.ConfigLogProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

/**
 * 配置变更日志
 */
@Data
@EntityProxy
@Table("config_log")
public class ConfigLog implements ProxyEntityAvailable<ConfigLog , ConfigLogProxy> {
    @Column(primaryKey = true, generatedKey = true)
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

    @Column
    private String project;    /** 项目（冗余存储，避免JOIN查询） */

    @Column
    private String environment; /** 环境（冗余存储，避免JOIN查询） */
}