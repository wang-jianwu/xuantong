package cloud.xuantong.config.management.repository.impl;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.config.management.model.ConfigResource;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.config.management.repository.ConfigResourceRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;

import java.util.Date;
import java.util.List;

@Component
public class ConfigResourceRepositoryImpl implements ConfigResourceRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public ConfigResource find(ConfigResourceKey key) {
        return easyQuery.queryable(ConfigResource.class)
                .where(o -> {
                    o.namespaceId().eq(key.namespaceId());
                    o.groupName().eq(key.groupName());
                    o.dataId().eq(key.dataId());
                })
                .firstOrNull();
    }

    @Override
    public List<ConfigResource> findAll() {
        return easyQuery.queryable(ConfigResource.class)
                .orderBy(o -> {
                    o.namespaceId().asc();
                    o.groupName().asc();
                    o.dataId().asc();
                })
                .toList();
    }

    @Override
    public List<ConfigResource> findByGroup(String namespaceId, String groupName) {
        return easyQuery.queryable(ConfigResource.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                })
                .orderBy(o -> o.dataId().asc())
                .toList();
    }

    @Override
    public PageResult<ConfigResource> findPage(
            String namespaceId,
            String groupName,
            String keyword,
            String lifecycleStatus,
            PageQuery pageQuery) {
        String normalizedKeyword = normalize(keyword);
        String normalizedLifecycle = normalize(lifecycleStatus);
        var query = easyQuery.queryable(ConfigResource.class)
                .where(o -> {
                    o.namespaceId().eq(namespaceId);
                    o.groupName().eq(groupName);
                    o.lifecycleStatus().eq(normalizedLifecycle != null, normalizedLifecycle);
                    if (normalizedKeyword != null) {
                        o.or(() -> {
                            o.dataId().contains(normalizedKeyword);
                            o.description().contains(normalizedKeyword);
                        });
                    }
                })
                .orderBy(o -> o.dataId().asc());
        var result = query.toPageResult(pageQuery.page(), pageQuery.pageSize());
        return PageResult.of(pageQuery, result.getTotal(), result.getData());
    }

    @Override
    public long save(ConfigResource resource) {
        return easyQuery.insertable(resource).executeRows(true);
    }

    @Override
    public long updateDraft(ConfigResource resource, long expectedDraftRevision) {
        return easyQuery.updatable(ConfigResource.class)
                .setColumns(o -> o.content().set(resource.getContent()))
                .setColumns(o -> o.contentType().set(resource.getContentType()))
                .setColumns(o -> o.checksum().set(resource.getChecksum()))
                .setColumns(o -> o.description().set(resource.getDescription()))
                .setColumns(o -> o.updatedBy().set(resource.getUpdatedBy()))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .setColumns(o -> o.draftRevision().set(expectedDraftRevision + 1L))
                .where(o -> {
                    o.id().eq(resource.getId());
                    o.draftRevision().eq(expectedDraftRevision);
                })
                .executeRows();
    }

    @Override
    public long updateRevision(
            Long configId, long expectedRevision, String expectedChecksum, long newRevision) {
        return easyQuery.updatable(ConfigResource.class)
                .setColumns(o -> o.revision().set(newRevision))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> {
                    o.id().eq(configId);
                    o.revision().eq(expectedRevision);
                    o.checksum().eq(expectedChecksum);
                })
                .executeRows();
    }

    @Override
    public long advanceLifecycle(Long configId, long newRevision, String lifecycleStatus) {
        return easyQuery.updatable(ConfigResource.class)
                .setColumns(o -> o.revision().set(newRevision))
                .setColumns(o -> o.lifecycleStatus().set(lifecycleStatus))
                .setColumns(o -> o.updatedAt().set(new Date()))
                .where(o -> {
                    o.id().eq(configId);
                    o.revision().lt(newRevision);
                })
                .executeRows();
    }

    @Override
    public long deleteUnpublishedDraft(ConfigResourceKey key) {
        return easyQuery.deletable(ConfigResource.class)
                .allowDeleteStatement(true)
                .where(o -> {
                    o.namespaceId().eq(key.namespaceId());
                    o.groupName().eq(key.groupName());
                    o.dataId().eq(key.dataId());
                    o.revision().eq(0L);
                })
                .executeRows();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
