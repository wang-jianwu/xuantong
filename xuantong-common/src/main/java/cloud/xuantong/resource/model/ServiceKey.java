package cloud.xuantong.resource.model;

public record ServiceKey(String namespaceId, String groupName, String serviceName) {
    public static ServiceKey of(String namespaceId, String groupName, String serviceName) {
        return new ServiceKey(
                ResourceNameRules.validate("namespaceId", namespaceId),
                ResourceNameRules.validate("groupName", groupName),
                ResourceNameRules.validate("serviceName", serviceName));
    }

    public String canonicalName() {
        return namespaceId + "/" + groupName + "/" + serviceName;
    }
}
