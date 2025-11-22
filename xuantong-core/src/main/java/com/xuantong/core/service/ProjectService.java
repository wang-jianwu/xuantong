package com.xuantong.core.service;

import com.xuantong.core.model.Project;
import com.xuantong.core.repository.ProjectRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.List;

@Component
public class ProjectService {
    @Inject
    private ProjectRepository projectRepository;

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Project getProject(String code) {
        return projectRepository.findByCode(code);
    }

    public boolean saveProject(Project project) {
        // 检查项目代码是否已存在
        Project existing = projectRepository.findByCode(project.getCode());
        if (existing != null && !existing.getId().equals(project.getId())) {
            throw new RuntimeException("项目代码已存在");
        }
        return projectRepository.save(project) > 0;
    }

    public boolean updateProject(Project project) {
        return projectRepository.update(project) > 0;
    }

    public boolean setProjectActive(String code, boolean isActive) {
        return projectRepository.setActive(code, isActive) > 0;
    }

    public long getProjectCount() {
        return projectRepository.countAll();
    }
}