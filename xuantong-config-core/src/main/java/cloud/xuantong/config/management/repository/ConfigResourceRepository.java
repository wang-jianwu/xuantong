package cloud.xuantong.config.management.repository;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;

import java.util.List;

public interface ConfigResourceRepository {
    ConfigResource find(ConfigResourceKey key);
    default List<ConfigResource> findAll() {
        return List.of();
    }
    List<ConfigResource> findByGroup(String namespaceId, String groupName);
    PageResult<ConfigResource> findPage(
            String namespaceId, String groupName, String keyword,
            String lifecycleStatus, PageQuery pageQuery);
    long save(ConfigResource resource);
    long updateDraft(ConfigResource resource, long expectedDraftRevision);
    long updateRevision(Long configId, long expectedRevision, String expectedChecksum, long newRevision);
    default long advanceLifecycle(Long configId, long newRevision, String lifecycleStatus) {
        throw new UnsupportedOperationException("State projection is not supported");
    }
    long deleteUnpublishedDraft(ConfigResourceKey key);
}
