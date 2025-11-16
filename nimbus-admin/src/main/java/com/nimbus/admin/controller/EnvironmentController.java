package com.nimbus.admin.controller;

import com.nimbus.core.model.Environment;
import com.nimbus.core.service.EnvironmentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/env")
@Api(produces = "环境管理接口")
public class EnvironmentController {
    @Inject
    private EnvironmentService environmentService;

    @ApiOperation(value = "获取所有环境")
    @Get
    @Mapping
    public Result<List<Environment>> getAllEnvironments() {
        List<Environment> envs = environmentService.getAllEnvironments();
        return Result.succeed(envs);
    }

    @ApiOperation(value = "获取指定环境")
    @Get
    @Mapping("/{code}")
    public Result<Environment> getEnvironment(
            @ApiParam(value = "环境代码") @Path String code) {
        Environment env = environmentService.getEnvironment(code);
        return env != null ? Result.succeed(env) : Result.failure("环境不存在");
    }

    @ApiOperation(value = "保存环境")
    @Post
    @Mapping
    public Result<String> saveEnvironment(
            @ApiParam(value = "环境对象") @Body Environment env) {
        boolean success = environmentService.saveEnvironment(env);
        return success ? Result.succeed("保存成功") : Result.failure("保存失败");
    }

    @ApiOperation(value = "设置默认环境")
    @Put
    @Mapping("/default/{code}")
    public Result<String> setDefaultEnvironment(
            @ApiParam(value = "环境代码") @Path String code) {
        boolean success = environmentService.setDefaultEnvironment(code);
        return success ? Result.succeed("设置成功") : Result.failure("设置失败");
    }
}