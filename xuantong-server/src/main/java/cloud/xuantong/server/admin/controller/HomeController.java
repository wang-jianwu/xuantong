package cloud.xuantong.server.admin.controller;

import cloud.xuantong.server.admin.security.AdminSecurityContext;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ModelAndView;


@Controller
public class HomeController {

    /**
     * 重定向未登录用户到登录页
     * @return null 表示重定向
     */
    private ModelAndView redirectIfNotLoggedIn(Context context) {
        if (AdminSecurityContext.currentUser(context) == null) {
            context.redirect("/login");
            return null;
        }
        return new ModelAndView(); // 临时对象，页面方法会覆盖
    }

    @Mapping("/")
    public ModelAndView home(Context context) {
        ModelAndView result = redirectIfNotLoggedIn(context);
        if (result == null) return null;
        context.redirect("/dashboard");
        return null;
    }

    @Mapping("/login")
    public ModelAndView login(Context context) {
        // 如果已登录，直接跳转到配置管理页
        if (AdminSecurityContext.currentUser(context) != null) {
            context.redirect("/dashboard");
            return null;
        }

        ModelAndView mv = new ModelAndView("login.shtm");
        mv.put("pageTitle", "登录 - 玄同");
        mv.put("user", AdminSecurityContext.currentUser(context));
        return mv;
    }

    private ModelAndView page(Context context, String template, String title) {
        ModelAndView result = redirectIfNotLoggedIn(context);
        if (result == null) return null;
        ModelAndView mv = new ModelAndView(template);
        mv.put("user", AdminSecurityContext.currentUser(context));
        mv.put("pageTitle", title + " - 玄同");
        return mv;
    }

    @Mapping("/config")
    public ModelAndView config(Context context) {
        return page(context, "config.shtm", "配置管理");
    }

    @Mapping("/dashboard")
    public ModelAndView dashboard(Context context) {
        return page(context, "dashboard.shtm", "运行概览");
    }

    @Mapping("/namespace")
    public ModelAndView namespace(Context context) {
        return page(context, "namespace.shtm", "命名空间");
    }

    @Mapping("/service")
    public ModelAndView service(Context context) {
        return page(context, "service.shtm", "服务管理");
    }

    @Mapping("/user")
    public ModelAndView user(Context context) {
        return page(context, "user.shtm", "用户管理");
    }

    @Mapping("/token")
    public ModelAndView token(Context context) {
        return page(context, "token.shtm", "访问令牌");
    }

    @Mapping("/audit")
    public ModelAndView audit(Context context) {
        return page(context, "audit.shtm", "审计日志");
    }

    @Mapping("/connection")
    public ModelAndView connection(Context context) {
        return page(context, "connection.shtm", "客户端连接");
    }

}
