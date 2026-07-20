package cloud.xuantong.config.management.service;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.model.AuditLog;
import cloud.xuantong.config.management.repository.AuditLogFilter;
import cloud.xuantong.config.management.repository.AuditLogRepository;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.Date;
import java.util.UUID;

/** Writes and reads audit records through a mandatory redaction boundary. */
@Component
public class AuditLogService {
    @Inject
    private AuditLogRepository repository;

    public PageResult<AuditLogView> findPage(
            AuditLogFilter filter, PageQuery pageQuery) {
        return repository.findPage(filter, pageQuery).map(this::view);
    }

    public AuditLogView view(AuditLog log) {
        return new AuditLogView(
                log.getId(),
                log.getNamespaceId(),
                log.getGroupName(),
                log.getResourceType(),
                log.getResourceName(),
                log.getOperation(),
                log.getOperator(),
                AuditDetailSanitizer.sanitize(log.getDetail()),
                log.getIpAddress(),
                log.getOperationId(),
                log.getCreatedAt());
    }

    public void record(
            String namespaceId,
            String groupName,
            String resourceType,
            String resourceName,
            String operation,
            String operator,
            Object detail,
            String ipAddress,
            String operationId) {
        AuditLog log = new AuditLog();
        log.setNamespaceId(namespaceId);
        log.setGroupName(groupName);
        log.setResourceType(resourceType);
        log.setResourceName(resourceName);
        log.setOperation(operation);
        log.setOperator(operator == null || operator.isBlank() ? "system" : operator);
        String serialized = detail == null
                ? null
                : detail instanceof String text ? text : ONode.serialize(detail);
        log.setDetail(AuditDetailSanitizer.sanitize(serialized));
        log.setIpAddress(ipAddress);
        log.setOperationId(operationId == null || operationId.isBlank()
                ? UUID.randomUUID().toString()
                : operationId);
        log.setCreatedAt(new Date());
        if (repository.save(log) != 1) {
            throw new IllegalStateException("Failed to persist audit record");
        }
    }

    public record AuditLogView(
            Long id,
            String namespaceId,
            String groupName,
            String resourceType,
            String resourceName,
            String operation,
            String operator,
            String detail,
            String ipAddress,
            String operationId,
            Date createdAt) {
    }
}
