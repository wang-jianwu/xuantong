package com.xuantong.admin.controller;

import com.xuantong.core.model.Project;
import com.xuantong.core.service.ProjectService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/project")
@Api(produces = "项目管理接口")
public class ProjectController {
    @Inject
    private ProjectService projectService;

    @ApiOperation(value = "获取所有项目")
    @Get
    @Mapping
    public Result getAllProjects() {
        List<Project> projects = projectService.getAllProjects();
        return Result.succeed(projects);
    }

    @ApiOperation(value = "获取指定项目")
    @Get
    @Mapping("/{code}")
    public Result getProject(
            @ApiParam(value = "项目代码") @Path String code) {
        Project project = projectService.getProject(code);
        return project != null ? Result.succeed(project) : Result.failure("项目不存在");
    }

    @ApiOperation(value = "保存项目")
    @Post
    @Mapping
    public Result saveProject(
            @ApiParam(value = "项目对象") @Body Project project) {
        try {
            boolean success = projectService.saveProject(project);
            return success ? Result.succeed("保存成功") : Result.failure("保存失败");
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ApiOperation(value = "更新项目")
    @Put
    @Mapping("/{code}")
    public Result updateProject(
            @ApiParam(value = "项目代码") @Path String code,
            @ApiParam(value = "项目对象") @Body Project project) {
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

    @ApiOperation(value = "设置项目激活状态")
    @Put
    @Mapping("/active/{code}/{active}")
    public Result setProjectActive(
            @ApiParam(value = "项目代码") @Path String code,
            @ApiParam(value = "激活状态") @Path boolean active) {
        boolean success = projectService.setProjectActive(code, active);
        return success ? Result.succeed("操作成功") : Result.failure("操作失败");
    }
}