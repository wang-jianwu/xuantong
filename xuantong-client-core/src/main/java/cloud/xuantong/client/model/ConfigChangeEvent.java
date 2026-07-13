package cloud.xuantong.client.model;

public class ConfigChangeEvent {
    private final String namespace;
    private final String group;
    private final String dataId;
    private final String content;
    private final long revision;

    public ConfigChangeEvent(String namespace, String group, String dataId, String content, long revision) {
        this.namespace = namespace;
        this.group = group;
        this.dataId = dataId;
        this.content = content;
        this.revision = revision;
    }

    public String getNamespace() { return namespace; }
    public String getGroup() { return group; }
    public String getDataId() { return dataId; }
    public String getNewValue() { return content; }
    public long getRevision() { return revision; }
}
