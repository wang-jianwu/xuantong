package cloud.xuantong.core.v2.repository;

import cloud.xuantong.core.v2.model.ConfigResource;
import cloud.xuantong.core.v2.model.ConfigResourceKey;

import java.util.List;

public interface ConfigResourceRepository {
    ConfigResource find(ConfigResourceKey key);
    List<ConfigResource> findByGroup(String namespaceId, String groupName);
    long save(ConfigResource resource);
    long updateDraft(ConfigResource resource);
    long updateRevision(Long configId, long expectedRevision, long newRevision);
    long deleteUnpublishedDraft(ConfigResourceKey key);
}
