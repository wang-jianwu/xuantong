package cloud.xuantong.resource.model;

import cloud.xuantong.resource.model.proxy.ResourceGroupProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("resource_group")
public class ResourceGroup implements ProxyEntityAvailable<ResourceGroup, ResourceGroupProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String description;
    @Column
    private String createdBy;
    @Column
    private Date createdAt;
}
