package cloud.xuantong.resource.service;

import cloud.xuantong.resource.model.ConfigNamespace;
import cloud.xuantong.resource.model.ConfigResourceKey;
import cloud.xuantong.resource.model.ResourceGroup;
import cloud.xuantong.resource.model.ResourceNameRules;
import cloud.xuantong.resource.repository.NamespaceRepository;
import cloud.xuantong.resource.repository.ResourceGroupRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.annotation.Transaction;

import java.util.Date;
import java.util.List;

@Component
public class NamespaceService {
    @Inject
    private NamespaceRepository namespaceRepository;
    @Inject
    private ResourceGroupRepository groupRepository;

    public List<ConfigNamespace> findAll() {
        return namespaceRepository.findAll();
    }

    public ConfigNamespace find(String namespaceId) {
        return namespaceRepository.findByNamespaceId(
                ResourceNameRules.validate("namespaceId", namespaceId));
    }

    public List<ResourceGroup> findGroups(String namespaceId) {
        return groupRepository.findByNamespace(
                ResourceNameRules.validate("namespaceId", namespaceId));
    }

    @Transaction
    public ConfigNamespace create(ConfigNamespace namespace, String operator) {
        String namespaceId = ResourceNameRules.validate("namespaceId", namespace.getNamespaceId());
        if (namespaceRepository.findByNamespaceId(namespaceId) != null) {
            throw new IllegalArgumentException("Namespace already exists: " + namespaceId);
        }
        namespace.setNamespaceId(namespaceId);
        namespace.setName(requireText("name", namespace.getName()));
        namespace.setIsActive(namespace.getIsActive() == null || namespace.getIsActive());
        namespace.setCreatedBy(operator);
        namespace.setCreatedAt(new Date());
        namespace.setUpdatedAt(new Date());
        namespaceRepository.save(namespace);

        ResourceGroup defaultGroup = new ResourceGroup();
        defaultGroup.setNamespaceId(namespaceId);
        defaultGroup.setGroupName(ConfigResourceKey.DEFAULT_GROUP);
        defaultGroup.setDescription("Default resource group");
        defaultGroup.setCreatedBy(operator);
        defaultGroup.setCreatedAt(new Date());
        groupRepository.save(defaultGroup);
        return namespace;
    }

    public ResourceGroup createGroup(String namespaceId, ResourceGroup group, String operator) {
        namespaceId = ResourceNameRules.validate("namespaceId", namespaceId);
        String groupName = ResourceNameRules.validate("groupName", group.getGroupName());
        if (namespaceRepository.findByNamespaceId(namespaceId) == null) {
            throw new IllegalArgumentException("Namespace does not exist: " + namespaceId);
        }
        if (groupRepository.find(namespaceId, groupName) != null) {
            throw new IllegalArgumentException("Group already exists: " + groupName);
        }
        group.setNamespaceId(namespaceId);
        group.setGroupName(groupName);
        group.setCreatedBy(operator);
        group.setCreatedAt(new Date());
        groupRepository.save(group);
        return group;
    }

    private String requireText(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
