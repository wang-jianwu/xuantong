package cloud.xuantong.resource.repository;

import cloud.xuantong.resource.model.ConfigNamespace;

import java.util.List;

public interface NamespaceRepository {
    List<ConfigNamespace> findAll();
    ConfigNamespace findByNamespaceId(String namespaceId);
    long save(ConfigNamespace namespace);
    long update(ConfigNamespace namespace);
}
