package cloud.xuantong.core.v2.repository;

import cloud.xuantong.core.v2.model.ConfigNamespace;

import java.util.List;

public interface NamespaceRepository {
    List<ConfigNamespace> findAll();
    ConfigNamespace findByNamespaceId(String namespaceId);
    long save(ConfigNamespace namespace);
    long update(ConfigNamespace namespace);
}
