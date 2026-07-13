package cloud.xuantong.core.v2.model;

import cloud.xuantong.core.v2.model.proxy.AuditLogProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("audit_log")
public class AuditLog implements ProxyEntityAvailable<AuditLog, AuditLogProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String resourceType;
    @Column
    private String resourceName;
    @Column
    private String operation;
    @Column
    private String operator;
    @Column
    private String detail;
    @Column
    private String ipAddress;
    @Column
    private Date createdAt;
}
