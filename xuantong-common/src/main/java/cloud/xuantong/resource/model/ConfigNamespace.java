package cloud.xuantong.resource.model;

import cloud.xuantong.resource.model.proxy.ConfigNamespaceProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("config_namespace")
public class ConfigNamespace implements ProxyEntityAvailable<ConfigNamespace, ConfigNamespaceProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String namespaceId;
    @Column
    private String name;
    @Column
    private String description;
    @Column
    private Boolean isActive;
    @Column
    private String labels;
    @Column
    private String createdBy;
    @Column
    private Date createdAt;
    @Column
    private Date updatedAt;
}
