package cloud.xuantong.config.management.model;

import cloud.xuantong.config.management.model.proxy.ConfigStateOperationProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;

import java.util.Date;

/**
 * Recoverable database record for a Config State command.
 *
 * <p>The Raft state machine remains authoritative. This row only records the
 * original immutable command and the progress of rebuilding SQL projections
 * and audit data after a committed operation.</p>
 */
@Data
@EntityProxy
@Table("config_state_operation")
public class ConfigStateOperation implements
        ProxyEntityAvailable<ConfigStateOperation, ConfigStateOperationProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    @Column
    private String operationId;
    @Column
    private String tenant;
    @Column
    private String principal;
    @Column
    private String namespaceId;
    @Column
    private String groupName;
    @Column
    private String dataId;
    @Column
    private String operationType;
    @Column
    private String requestHash;
    @Column
    private String commandType;
    @Column
    private Integer schemaVersion;
    @Column
    private String commandPayload;
    @Column
    private String projectionPayload;
    @Column
    private String status;
    @Column
    private Long contentRevision;
    @Column
    private Long decisionRevision;
    @Column
    private Long eventRevision;
    @Column
    private String errorMessage;
    @Column
    private Date createdAt;
    @Column
    private Date updatedAt;
}
