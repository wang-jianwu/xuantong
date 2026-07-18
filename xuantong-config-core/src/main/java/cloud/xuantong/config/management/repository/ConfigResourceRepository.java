package cloud.xuantong.config.management.repository;

import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;

import java.util.List;

public interface ConfigResourceRepository {
    ConfigResource find(ConfigResourceKey key);
    List<ConfigResource> findByGroup(String namespaceId, String groupName);
    long save(ConfigResource resource);
    long updateDraft(ConfigResource resource);
    long updateRevision(Long configId, long expectedRevision, String expectedChecksum, long newRevision);
    default long advanceRevision(Long configId, long newRevision) {
        throw new UnsupportedOperationException("State projection is not supported");
    }
    long deleteUnpublishedDraft(ConfigResourceKey key);
}
