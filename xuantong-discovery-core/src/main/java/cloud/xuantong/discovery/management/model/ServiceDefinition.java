package cloud.xuantong.discovery.management.model;

import cloud.xuantong.discovery.management.model.proxy.ServiceDefinitionProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("service_definition")
public class ServiceDefinition implements ProxyEntityAvailable<ServiceDefinition, ServiceDefinitionProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String serviceName;
    @Column
    private String description;
    @Column
    private String metadata;
    @Column
    private Long serviceGeneration;
    @Column
    private String lifecycleState;
    @Column
    private String lifecycleOperationId;
    @Column
    private String lifecycleError;
    @Column
    private String createdBy;
    @Column
    private Date createdAt;
    @Column
    private Date updatedAt;
}
