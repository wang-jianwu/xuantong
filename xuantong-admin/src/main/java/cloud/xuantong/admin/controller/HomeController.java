package cloud.xuantong.admin.controller;

import cloud.xuantong.core.cluster.ClusterSyncPlayer;
import cloud.xuantong.core.listener.ConfigBrokerListener;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ModelAndView;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Controller
public class HomeController {

    @Inject
    private ConfigBrokerListener brokerListener;

    @Inject(required = false)
    private ClusterSyncPlayer clusterSyncPlayer;

    /**
     * 重定向未登录用户到登录页
     * @return null 表示重定向
     */
    private ModelAndView redirectIfNotLoggedIn(Context context) {
        if (context.session("user") == null) {
            context.redirect("/login");
            return null;
        }
        return new ModelAndView(); // 临时对象，页面方法会覆盖
    }

    @Mapping("/")
    public ModelAndView home(Context context) {
        ModelAndView result = redirectIfNotLoggedIn(context);
        if (result == null) return null;
        return new ModelAndView("dashboard.shtm");
    }

    @Mapping("/login")
    public ModelAndView login(Context context) {
        // 如果已登录，直接跳转到仪表盘
        if (context.session("user") != null) {
            context.redirect("/dashboard");
            return null;
        }

        ModelAndView mv = new ModelAndView("login.shtm");
        mv.put("pageTitle", "登录 - Xuantong Config");
        mv.put("user", context.session("user"));
        return mv;
    }

    private ModelAndView page(Context context, String template, String title) {
        ModelAndView result = redirectIfNotLoggedIn(context);
        if (result == null) return null;
        ModelAndView mv = new ModelAndView(template);
        mv.put("user", context.session("user"));
        mv.put("pageTitle", title + " - Xuantong Config");
        return mv;
    }

    @Mapping("/dashboard")
    public ModelAndView dashboard(Context context) {
        return page(context, "dashboard.shtm", "仪表盘");
    }

    @Mapping("/config")
    public ModelAndView config(Context context) {
        return page(context, "config.shtm", "配置管理");
    }

    @Mapping("/project")
    public ModelAndView project(Context context) {
        return page(context, "project.shtm", "项目管理");
    }

    @Mapping("/env")
    public ModelAndView environment(Context context) {
        return page(context, "environment.shtm", "环境管理");
    }

    @Mapping("/user")
    public ModelAndView user(Context context) {
        return page(context, "user.shtm", "用户管理");
    }

    @Mapping("/broker")
    public ModelAndView broker(Context context) {
        return page(context, "broker.shtm", "Broker 监控");
    }

    /**
     * 健康检查接口（无需登录）
     */
    @Mapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("broker", "UP");
        status.put("playerCount", brokerListener.getActivePlayerCount());
        status.put("clusterConnections", clusterSyncPlayer != null ? clusterSyncPlayer.getActiveConnectionCount() : 0);
        return status;
    }

    /**
     * 获取连接的客户端列表
     */
    @Mapping("/api/broker/players")
    public Object getPlayers(Context context) {
        return brokerListener.getActivePlayers();
    }

    /**
     * 获取推送日志
     */
    @Mapping("/api/broker/push-logs")
    public Object getPushLogs(Context context) {
        return brokerListener.getPushLogs();
    }

    /**
     * Broker 总览
     */
    @Mapping("/api/broker/overview")
    public Object getBrokerOverview(Context context) {

        Map<String, Object> overview = new HashMap<>();
        overview.put("activePlayers", brokerListener.getActivePlayers());
        overview.put("playerCount", brokerListener.getActivePlayerCount());
        overview.put("recentPushLogs", brokerListener.getPushLogs());
        overview.put("clusterConnections", clusterSyncPlayer != null ? clusterSyncPlayer.getActiveConnectionCount() : 0);
        return overview;
    }
}