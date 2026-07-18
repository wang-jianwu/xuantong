package cloud.xuantong.resource.repository;

import cloud.xuantong.resource.model.ResourceGroup;

import java.util.List;

public interface ResourceGroupRepository {
    List<ResourceGroup> findByNamespace(String namespaceId);
    ResourceGroup find(String namespaceId, String groupName);
    long save(ResourceGroup group);
}
