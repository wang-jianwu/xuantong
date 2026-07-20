package cloud.xuantong.config.management.repository;

/** Optional SQL-side filters for the management audit log. */
public record AuditLogFilter(
        String namespaceId,
        String groupName,
        String resourceType,
        String resourceName,
        String operation,
        String operator,
        String keyword) {

    public static AuditLogFilter recent(
            String resourceType, String operation, String keyword) {
        return new AuditLogFilter(null, null, resourceType, null,
                operation, null, keyword);
    }

    public static AuditLogFilter resource(
            String namespaceId,
            String groupName,
            String resourceType,
            String resourceName) {
        return new AuditLogFilter(namespaceId, groupName, resourceType, resourceName,
                null, null, null);
    }
}
