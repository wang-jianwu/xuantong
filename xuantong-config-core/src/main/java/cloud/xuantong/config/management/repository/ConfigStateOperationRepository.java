package cloud.xuantong.config.management.repository;

import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.model.ConfigStateOperationStatus;

import java.util.List;

public interface ConfigStateOperationRepository {
    long save(ConfigStateOperation operation);

    ConfigStateOperation find(String tenant, String principal, String operationId);

    default ConfigStateOperation findUnfinishedForConfig(
            String namespaceId, String groupName, String dataId) {
        return null;
    }

    default ConfigStateOperation findAnyNonFailedForConfig(
            String namespaceId, String groupName, String dataId) {
        return null;
    }

    List<ConfigStateOperation> findRecoverable(int limit);

    long markCommitted(
            Long id, long contentRevision, long decisionRevision, long eventRevision);

    long markProjectionPending(Long id, String errorMessage);

    long markProjected(Long id);

    long markFailed(Long id, String errorMessage);

    long updatePendingError(Long id, String errorMessage);

    long updateStatus(
            Long id, ConfigStateOperationStatus expected, ConfigStateOperationStatus status);
}
