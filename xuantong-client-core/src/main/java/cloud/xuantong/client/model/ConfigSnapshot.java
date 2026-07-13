package cloud.xuantong.client.model;

public class ConfigSnapshot {
    private final String dataId;
    private final String content;
    private final long revision;
    private final String checksum;
    private final String contentType;

    public ConfigSnapshot(String dataId, String content, long revision, String checksum, String contentType) {
        this.dataId = dataId;
        this.content = content;
        this.revision = revision;
        this.checksum = checksum;
        this.contentType = contentType;
    }

    public String getDataId() { return dataId; }
    public String getContent() { return content; }
    public long getRevision() { return revision; }
    public String getChecksum() { return checksum; }
    public String getContentType() { return contentType; }
}
