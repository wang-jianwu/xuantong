package cloud.xuantong.admin.controller;

import com.easy.query.core.api.pagination.EasyPageResult;
import cloud.xuantong.core.cluster.ConfigClusterBroadcaster;
import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import cloud.xuantong.core.model.ChangeVo;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.model.ConfigLog;
import cloud.xuantong.core.service.ConfigService;
import cloud.xuantong.core.service.ProjectService;
import cloud.xuantong.core.service.UserService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@Mapping("/api/config")
public class ConfigController {
    @Inject
    private ConfigService configService;

    @Inject
    private ProjectService projectService;

    @Inject
    private UserService userService;

    @Inject
    private ConfigClusterBroadcaster clusterBroadcaster;

    @Get
    @Mapping("/{project}/{environment}/{key}")
    public Result<ConfigItem> getConfig(
            @Path String project,
            @Path String environment,
            @Path String key) {
        ConfigItem config = configService.getConfig(key, environment, project);
        return config != null ? Result.succeed(config) : Result.failure("配置不存在");
    }

    @Get
    @Mapping("/{project}/{environment}")
    public Result<EasyPageResult<ConfigItem>> getProjectConfigs(
            @Path String project,
            @Path String environment,
            @Param(required = false) String keyWords,
            @Param(required = false, defaultValue = "1") Long pn,
            @Param(required = false, defaultValue = "10") Long size
    ) {
        // 设置默认值
        if (pn == null || pn < 1) pn = 1L;
        if (size == null || size < 1) size = 10L;
        if (size > 100) size = 100L; // 限制最大页大小

        EasyPageResult<ConfigItem> configs = configService.getProjectConfigs(project, environment, keyWords, pn, size);
        return Result.succeed(configs);
    }

    @Post
    @Mapping
    public Result<String> saveConfig(
            @Body ConfigItem config,
            @Param(defaultValue = "none") String pushMode,
            @Param(defaultValue = "") String targetIp,
            @Param(defaultValue = "0") double percentage) {
        boolean success = configService.saveConfig(config, pushMode);
        if (success) {
            // 灰度推送时支持 IP 和比例
            if ("gray".equals(pushMode) && (targetIp != null && !targetIp.isEmpty())) {
                ConfigChangeEvent event = new ConfigChangeEvent(
                        config.getKey(), config.getValue(),
                        config.getProject(), config.getEnvironment()
                );
                clusterBroadcaster.broadcastConfigChange(event, true, targetIp, 0);
                return Result.succeed("保存成功，灰度推送（指定IP: " + targetIp + "）");
            }
            if ("gray".equals(pushMode) && percentage > 0 && percentage < 1) {
                ConfigChangeEvent event = new ConfigChangeEvent(
                        config.getKey(), config.getValue(),
                        config.getProject(), config.getEnvironment()
                );
                clusterBroadcaster.broadcastConfigChange(event, true, null, percentage);
                return Result.succeed("保存成功，灰度推送（比例: " + (int)(percentage * 100) + "%）");
            }
            switch (pushMode) {
                case "gray": return Result.succeed("保存成功，灰度推送（随机1台）");
                case "all":  return Result.succeed("保存成功，全量推送");
                default:     return Result.succeed("保存成功（未推送）");
            }
        }
        return Result.failure("保存失败");
    }

    /**
     * 推送配置（不保存，只推送当前值）
     */
    @Post
    @Mapping("/push")
    public Result<String> pushConfig(@Body ConfigItem config,
                                     @Param(defaultValue = "all") String pushMode,
                                     @Param(defaultValue = "") String targetIp,
                                     @Param(defaultValue = "0") double percentage) {
        ConfigChangeEvent event = new ConfigChangeEvent(
                config.getKey(), config.getValue(),
                config.getProject(), config.getEnvironment()
        );
        boolean gray = "gray".equals(pushMode);

        if (targetIp != null && !targetIp.isEmpty()) {
            clusterBroadcaster.broadcastConfigChange(event, true, targetIp, 0);
            return Result.succeed("灰度推送成功（指定IP: " + targetIp + "）");
        }
        if (percentage > 0 && percentage < 1) {
            clusterBroadcaster.broadcastConfigChange(event, true, null, percentage);
            return Result.succeed("灰度推送成功（比例: " + (int)(percentage * 100) + "%）");
        }

        clusterBroadcaster.broadcastConfigChange(event, gray);
        return Result.succeed(gray ? "灰度推送成功（随机1台）" : "全量推送成功");
    }

    @Get
    @Mapping("/detail/{id}")
    public Result<ConfigItem> getConfigDetail(
            @Path Long id) {
        ConfigItem config = configService.getConfigById(id);
        return config != null ? Result.succeed(config) : Result.failure("配置不存在");
    }

    @Delete
    @Mapping("/{id}")
    public Result<String> deleteConfig(
            @Path Long id) {
        boolean success = configService.deleteConfig(id);
        return success ? Result.succeed("删除成功") : Result.failure("删除失败");
    }

    @Put
    @Mapping("/revert/{logId}")
    public Result<String> revertToVersion(
            @Path Long logId) {
        boolean success = configService.revertToVersion(logId);
        return success ? Result.succeed("回退成功") : Result.failure("回退失败");
    }

    @Get
    @Mapping("/history/get/list/{configId}")
    public Result<List<ConfigLog>> getConfigHistory(
            @Path Long configId) {
        List<ConfigLog> history = configService.getConfigHistory(configId);
        return Result.succeed(history);
    }

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

    @Get
    @Mapping("/changes")
    public Result<List<ChangeVo>> getRecentChanges() {
        List<ChangeVo> changes = configService.getRecentChanges(5);
        return Result.succeed(changes);
    }
}