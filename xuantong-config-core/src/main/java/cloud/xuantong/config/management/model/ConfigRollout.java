package cloud.xuantong.config.management.model;

import cloud.xuantong.config.management.model.proxy.ConfigRolloutProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

@Data
@EntityProxy
@Table("config_rollout")
public class ConfigRollout implements ProxyEntityAvailable<ConfigRollout, ConfigRolloutProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String rolloutId;
    @Column
    private Long configId;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String dataId;
    @Column
    private String baselineReleaseId;
    @Column
    private String candidateReleaseId;
    @Column
    private String rolloutType;
    @Column
    private String targetValue;
    @Column
    private String rolloutKey;
    @Column
    private String status;
    @Column
    private String createdBy;
    @Column
    private Date createdAt;
    @Column
    private String completedBy;
    @Column
    private Date completedAt;
    @Column
    private Long decisionRevision;
    @Column
    private String startOperationId;
    @Column
    private String completeOperationId;
}
