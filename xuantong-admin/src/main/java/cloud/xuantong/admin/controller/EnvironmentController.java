package cloud.xuantong.admin.controller;

import cloud.xuantong.core.model.Environment;
import cloud.xuantong.core.service.EnvironmentService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/env")
public class EnvironmentController {
    @Inject
    private EnvironmentService environmentService;

    @Get
    @Mapping
    public Result<List<Environment>> getAllEnvironments() {
        List<Environment> envs = environmentService.getAllEnvironments();
        return Result.succeed(envs);
    }

    @Get
    @Mapping("/{code}")
    public Result<Environment> getEnvironment(
            @Path String code) {
        Environment env = environmentService.getEnvironment(code);
        return env != null ? Result.succeed(env) : Result.failure("环境不存在");
    }

    @Post
    @Mapping
    public Result<String> saveEnvironment(
            @Body Environment env) {
        boolean success = environmentService.saveEnvironment(env);
        return success ? Result.succeed("保存成功") : Result.failure("保存失败");
    }

    @Put
    @Mapping("/default/{code}")
    public Result<String> setDefaultEnvironment(
            @Path String code) {
        boolean success = environmentService.setDefaultEnvironment(code);
        return success ? Result.succeed("设置成功") : Result.failure("设置失败");
    }
}