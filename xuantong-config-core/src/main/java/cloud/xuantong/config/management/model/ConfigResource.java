package cloud.xuantong.config.management.model;

import cloud.xuantong.config.management.model.proxy.ConfigResourceProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("config_resource")
public class ConfigResource implements ProxyEntityAvailable<ConfigResource, ConfigResourceProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String dataId;
    @Column
    private String content;
    @Column
    private String contentType;
    @Column
    private String checksum;
    @Column
    private Long revision;
    @Column
    private Boolean isEncrypted;
    @Column
    private String description;
    @Column
    private String createdBy;
    @Column
    private String updatedBy;
    @Column
    private Date createdAt;
    @Column
    private Date updatedAt;
}
