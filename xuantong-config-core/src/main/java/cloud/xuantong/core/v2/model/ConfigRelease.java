package cloud.xuantong.core.v2.model;

import cloud.xuantong.core.v2.model.proxy.ConfigReleaseProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("config_release")
public class ConfigRelease implements ProxyEntityAvailable<ConfigRelease, ConfigReleaseProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String releaseId;
    @Column
    private Long configId;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String dataId;
    @Column
    private Long revision;
    @Column
    private String content;
    @Column
    private String contentType;
    @Column
    private String checksum;
    @Column
    private String releaseType;
    @Column
    private String operator;
    @Column
    private Date releasedAt;
}
