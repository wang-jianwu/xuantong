package cloud.xuantong.resource.model;

/** 2.0 配置资源的全局唯一业务标识。 */
public record ConfigResourceKey(String namespaceId, String groupName, String dataId) {
    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    public ConfigResourceKey {
        namespaceId = ResourceNameRules.validate("namespaceId", namespaceId);
        groupName = ResourceNameRules.validate("groupName", groupName);
        dataId = ResourceNameRules.validate("dataId", dataId);
    }

    public static ConfigResourceKey of(String namespaceId, String groupName, String dataId) {
        return new ConfigResourceKey(namespaceId, groupName, dataId);
    }

    public static ConfigResourceKey inDefaultGroup(String namespaceId, String dataId) {
        return new ConfigResourceKey(namespaceId, DEFAULT_GROUP, dataId);
    }

    public String canonicalName() {
        return namespaceId + "/" + groupName + "/" + dataId;
    }
}
