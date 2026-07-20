package cloud.xuantong.client.model;

public class ConfigSnapshot {
    private final ConfigSnapshotState state;
    private final String dataId;
    private final String content;
    private final long revision;
    private final String checksum;
    private final String contentType;

    public ConfigSnapshot(String dataId, String content, long revision, String checksum, String contentType) {
        this(ConfigSnapshotState.ACTIVE, dataId, content, revision, checksum, contentType);
    }

    private ConfigSnapshot(
            ConfigSnapshotState state,
            String dataId,
            String content,
            long revision,
            String checksum,
            String contentType) {
        if (state == null || dataId == null || dataId.isBlank() || revision < 1) {
            throw new IllegalArgumentException("Config snapshot requires state, dataId and revision");
        }
        if (state == ConfigSnapshotState.ACTIVE && content == null) {
            throw new IllegalArgumentException("Active Config snapshot requires content");
        }
        if (state == ConfigSnapshotState.TOMBSTONE
                && (content != null || checksum != null || contentType != null)) {
            throw new IllegalArgumentException("Tombstone snapshot must not carry content");
        }
        this.state = state;
        this.dataId = dataId;
        this.content = content;
        this.revision = revision;
        this.checksum = checksum;
        this.contentType = contentType;
    }

    public static ConfigSnapshot tombstone(String dataId, long revision) {
        return new ConfigSnapshot(
                ConfigSnapshotState.TOMBSTONE, dataId, null, revision, null, null);
    }

    public ConfigSnapshotState getState() { return state; }
    public boolean isTombstone() { return state == ConfigSnapshotState.TOMBSTONE; }
    public String getDataId() { return dataId; }
    public String getContent() { return content; }
    public long getRevision() { return revision; }
    public String getChecksum() { return checksum; }
    public String getContentType() { return contentType; }
}
