package com.xuantong.core.repository.impl;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import com.xuantong.core.model.Project;
import com.xuantong.core.repository.ProjectRepository;
import org.noear.solon.annotation.Component;

import java.util.List;

@Component
public class ProjectRepositoryImpl implements ProjectRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public List<Project> findAll() {
        return easyQuery.queryable(Project.class)
                .orderBy(o -> o.code().asc())
                .toList();
    }

    @Override
    public Project findByCode(String code) {
        return easyQuery.queryable(Project.class)
                .where(o -> o.code().eq(code))
                .firstOrNull();
    }

    @Override
    public long save(Project project) {
        return easyQuery.insertable(project).executeRows();
    }

    @Override
    public long update(Project project) {
        return easyQuery.updatable(project)
                .where(o -> o.code().eq(project.getCode()))
                .executeRows();
    }

    @Override
    public long setActive(String code, boolean isActive) {
        return easyQuery.updatable(Project.class)
                .setColumns(p -> p.isActive().set(isActive))
                .where(o -> o.code().eq(code))
                .executeRows();
    }

    @Override
    public long countAll() {
        return easyQuery.queryable(Project.class).count();
    }
}