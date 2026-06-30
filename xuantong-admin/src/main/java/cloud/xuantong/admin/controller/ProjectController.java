package cloud.xuantong.admin.controller;

import cloud.xuantong.core.model.Project;
import cloud.xuantong.core.service.ProjectService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/project")
public class ProjectController {
    @Inject
    private ProjectService projectService;

    @Get
    @Mapping
    public Result<List<Project>> getAllProjects() {
        List<Project> projects = projectService.getAllProjects();
        return Result.succeed(projects);
    }

    @Get
    @Mapping("/{code}")
    public Result<Project> getProject(
            @Path String code) {
        Project project = projectService.getProject(code);
        return project != null ? Result.succeed(project) : Result.failure("项目不存在");
    }

    @Post
    @Mapping
    public Result<String> saveProject(
            @Body Project project) {
        try {
            boolean success = projectService.saveProject(project);
            return success ? Result.succeed("保存成功") : Result.failure("保存失败");
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Put
    @Mapping("/{code}")
    public Result<String> updateProject(
            @Path String code,
            @Body Project project) {
        try {
            // 确保项目代码一致
            if (!code.equals(project.getCode())) {
                return Result.failure("项目代码不匹配");
            }
            boolean success = projectService.saveProject(project);
            return success ? Result.succeed("更新成功") : Result.failure("更新失败");
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Put
    @Mapping("/active/{code}/{active}")
    public Result<String> setProjectActive(
            @Path String code,
            @Path boolean active) {
        boolean success = projectService.setProjectActive(code, active);
        return success ? Result.succeed("操作成功") : Result.failure("操作失败");
    }
}