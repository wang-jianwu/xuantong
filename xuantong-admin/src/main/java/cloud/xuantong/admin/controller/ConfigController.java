package cloud.xuantong.admin.controller;

import com.easy.query.core.api.pagination.EasyPageResult;
import cloud.xuantong.core.cluster.ConfigPushEvent;
import cloud.xuantong.core.cluster.PushMode;
import cloud.xuantong.core.listener.BrokerMonitor;
import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import cloud.xuantong.core.model.ChangeVo;
import cloud.xuantong.core.model.ConfigItem;
import cloud.xuantong.core.model.ConfigLog;
import cloud.xuantong.core.service.ConfigService;
import cloud.xuantong.core.service.ProjectService;
import cloud.xuantong.core.service.UserService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.event.EventBus;
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
    private BrokerMonitor brokerMonitor;

    // ===== 查询 =====

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
        if (pn == null || pn < 1) pn = 1L;
        if (size == null || size < 1) size = 10L;
        if (size > 100) size = 100L;

        EasyPageResult<ConfigItem> configs = configService.getProjectConfigs(project, environment, keyWords, pn, size);
        return Result.succeed(configs);
    }

    @Get
    @Mapping("/detail/{id}")
    public Result<ConfigItem> getConfigDetail(@Path Long id) {
        ConfigItem config = configService.getConfigById(id);
        return config != null ? Result.succeed(config) : Result.failure("配置不存在");
    }

    @Get
    @Mapping("/history/get/list/{configId}")
    public Result<List<ConfigLog>> getConfigHistory(@Path Long configId) {
        List<ConfigLog> history = configService.getConfigHistory(configId);
        return Result.succeed(history);
    }

    @Get
    @Mapping("/stats")
    public Result<Map<String, Object>> getConfigStats() {
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

    // ===== 写入操作（Service 返回 Event，Controller 决定是否推送） =====

    @Post
    @Mapping
    public Result<String> saveConfig(
            @Body ConfigItem config,
            @Param(defaultValue = "none") String pushMode,
            @Param(defaultValue = "") String targetIp,
            @Param(defaultValue = "0") double percentage) {
        
        ConfigChangeEvent event = configService.saveConfig(config);
        if (event == null) {
            return Result.failure("保存失败");
        }

        if ("none".equals(pushMode)) {
            return Result.succeed("保存成功（未推送）");
        }

        return publishAndReturn(event, pushMode, targetIp, percentage, "保存");
    }

    @Delete
    @Mapping("/{id}")
    public Result<String> deleteConfig(@Path Long id) {
        ConfigItem config = configService.getConfigById(id);
        if (config == null) {
            return Result.failure("配置不存在");
        }

        ConfigChangeEvent event = configService.deleteConfig(config);
        if (event == null) {
            return Result.failure("删除失败");
        }
        
        // 删除后自动全量推送，通知客户端清除本地缓存
        return publishAndReturn(event, "all", "", 0, "删除");
    }

    @Put
    @Mapping("/revert/{logId}")
    public Result<String> revertToVersion(@Path Long logId) {
        ConfigChangeEvent event = configService.revertToVersion(logId);
        if (event == null) {
            return Result.failure("回退失败");
        }
        
        // 回退后自动全量推送
        return publishAndReturn(event, "all", "", 0, "回退");
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
        
        PushMode mode = resolvePushMode(pushMode, targetIp, percentage);
        try {
            EventBus.publish(new ConfigPushEvent(event, mode, targetIp, percentage));
            String msg = describeResult("", mode, targetIp, percentage);
            // 去掉开头的逗号
            if (msg.startsWith("，")) msg = msg.substring(1);
            return Result.succeed(msg);
        } catch (Exception e) {
            return Result.succeed("推送失败: " + e.getMessage());
        }
    }

    // ===== 辅助方法 =====

    private PushMode resolvePushMode(String pushMode, String targetIp, double percentage) {
        if (targetIp != null && !targetIp.isEmpty()) return PushMode.IP;
        if (percentage > 0 && percentage < 1) return PushMode.PERCENTAGE;
        if ("gray".equals(pushMode)) return PushMode.GRAY;
        return PushMode.ALL;
    }

    /**
     * 发布推送事件并返回结果
     * @param action 操作名称，如"保存"、"删除"、"回退"、"推送"
     */
    private Result<String> publishAndReturn(ConfigChangeEvent event, String pushMode,
                                            String targetIp, double percentage, String action) {
        try {
            PushMode mode = resolvePushMode(pushMode, targetIp, percentage);
            EventBus.publish(new ConfigPushEvent(event, mode, targetIp, percentage));
            return Result.succeed(describeResult(action, mode, targetIp, percentage));
        } catch (Exception e) {
            return Result.succeed(action + "成功，但推送失败: " + e.getMessage());
        }
    }

    private String describeResult(String action, PushMode mode, String targetIp, double percentage) {
        return switch (mode) {
            case IP -> action + "成功，灰度推送（指定IP: " + targetIp + "）";
            case PERCENTAGE -> action + "成功，灰度推送（比例: " + (int) (percentage * 100) + "%）";
            case GRAY -> action + "成功，灰度推送（随机1台）";
            default -> action + "成功，全量推送";
        };
    }
}
