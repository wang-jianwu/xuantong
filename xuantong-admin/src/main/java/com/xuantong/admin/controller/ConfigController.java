package com.xuantong.admin.controller;

import com.easy.query.core.api.pagination.EasyPageResult;
import com.xuantong.core.model.ConfigItem;
import com.xuantong.core.model.ConfigLog;
import com.xuantong.core.service.ConfigService;
import com.xuantong.core.service.ProjectService;
import com.xuantong.core.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@Mapping("/api/config")
@Api(produces = "配置项管理接口")
public class ConfigController {
    @Inject
    private ConfigService configService;

    @Inject
    private ProjectService projectService;

    @Inject
    private UserService userService;

    @ApiOperation(value = "获取配置项")
    @Get
    @Mapping("/{project}/{environment}/{key}")
    public Result<ConfigItem> getConfig(
            @ApiParam(value = "项目代码") @Path String project,
            @ApiParam(value = "环境代码") @Path String environment,
            @ApiParam(value = "配置键") @Path String key) {
        ConfigItem config = configService.getConfig(key, environment, project);
        return config != null ? Result.succeed(config) : Result.failure("配置不存在");
    }

    @ApiOperation(value = "获取项目配置列表")
    @Get
    @Mapping("/{project}/{environment}")
    public Result<EasyPageResult<ConfigItem>> getProjectConfigs(
            @ApiParam(value = "项目代码") @Path String project,
            @ApiParam(value = "环境代码") @Path String environment,
            @ApiParam(value = "搜索关键词") @Param(required = false) String keyWords,
            @ApiParam(value = "页码", defaultValue = "1") @Param(required = false, defaultValue = "1") Long pn,
            @ApiParam(value = "页大小", defaultValue = "10") @Param(required = false, defaultValue = "10") Long size
    ) {
        // 设置默认值
        if (pn == null || pn < 1) pn = 1L;
        if (size == null || size < 1) size = 10L;
        if (size > 100) size = 100L; // 限制最大页大小

        EasyPageResult<ConfigItem> configs = configService.getProjectConfigs(project, environment, keyWords, pn, size);
        return Result.succeed(configs);
    }

    @ApiOperation(value = "保存配置项")
    @Post
    @Mapping
    public Result<String> saveConfig(
            @ApiParam(value = "配置项对象") @Body ConfigItem config) {
        boolean success = configService.saveConfig(config);
        return success ? Result.succeed("保存成功") : Result.failure("保存失败");
    }

    @ApiOperation(value = "获取配置详情")
    @Get
    @Mapping("/detail/{id}")
    public Result<ConfigItem> getConfigDetail(
            @ApiParam(value = "配置ID") @Path Long id) {
        ConfigItem config = configService.getConfigById(id);
        return config != null ? Result.succeed(config) : Result.failure("配置不存在");
    }

    @ApiOperation(value = "删除配置项")
    @Delete
    @Mapping("/{id}")
    public Result<String> deleteConfig(
            @ApiParam(value = "配置ID") @Path Long id) {
        boolean success = configService.deleteConfig(id);
        return success ? Result.succeed("删除成功") : Result.failure("删除失败");
    }

    @ApiOperation(value = "回退到指定版本")
    @Put
    @Mapping("/revert/{logId}")
    public Result<String> revertToVersion(
            @ApiParam(value = "配置ID") @Path Long logId) {
        boolean success = configService.revertToVersion(logId);
        return success ? Result.succeed("回退成功") : Result.failure("回退失败");
    }

    @ApiOperation(value = "获取配置变更历史")
    @Get
    @Mapping("/history/get/list/{configId}")
    public Result<List<ConfigLog>> getConfigHistory(
            @ApiParam(value = "配置ID") @Path Long configId) {
        List<ConfigLog> history = configService.getConfigHistory(configId);
        return Result.succeed(history);
    }

    @ApiOperation(value = "获取配置统计信息")
    @Get
    @Mapping("/stats")
    public Result getConfigStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("configCount", configService.getConfigCount());
        stats.put("projectCount", projectService.getProjectCount());
        stats.put("userCount", userService.getUserCount());
        stats.put("changeCount", configService.getTodayChangeCount());
        return Result.succeed(stats);
    }

    @ApiOperation(value = "获取最近配置变更")
    @Get
    @Mapping("/changes")
    public Result getRecentChanges() {
        List<Map<String, Object>> changes = new ArrayList<>();

        // 模拟最近变更数据（Java 8兼容语法）
        Map<String, Object> change1 = new HashMap<>();
        change1.put("key", "database.url");
        change1.put("project", "demo");
        change1.put("environment", "dev");
        change1.put("operator", "admin");
        change1.put("time", "2024-01-15 10:30:25");
        changes.add(change1);

        Map<String, Object> change2 = new HashMap<>();
        change2.put("key", "redis.host");
        change2.put("project", "demo");
        change2.put("environment", "test");
        change2.put("operator", "admin");
        change2.put("time", "2024-01-15 09:15:42");
        changes.add(change2);

        Map<String, Object> change3 = new HashMap<>();
        change3.put("key", "app.version");
        change3.put("project", "production");
        change3.put("environment", "prod");
        change3.put("operator", "system");
        change3.put("time", "2024-01-14 16:20:33");
        changes.add(change3);

        return Result.succeed(changes);
    }
}