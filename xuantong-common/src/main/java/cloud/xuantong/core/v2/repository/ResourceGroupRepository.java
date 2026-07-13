package cloud.xuantong.core.v2.repository;

import cloud.xuantong.core.v2.model.ResourceGroup;

import java.util.List;

public interface ResourceGroupRepository {
    List<ResourceGroup> findByNamespace(String namespaceId);
    ResourceGroup find(String namespaceId, String groupName);
    long save(ResourceGroup group);
}
