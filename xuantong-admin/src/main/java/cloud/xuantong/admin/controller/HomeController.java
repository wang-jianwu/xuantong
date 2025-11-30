package cloud.xuantong.admin.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ModelAndView;

@Controller
public class HomeController {

    private boolean isLoggedIn(Context context) {
        return context.session("user") != null;
    }
    @Mapping("/")
    public ModelAndView home(Context context) {
        // 检查登录状态
        if (!isLoggedIn(context)) {
            context.redirect("/login");
            return null; // 返回null表示重定向
        }
        // 返回统一的单页面应用入口
        return new ModelAndView("dashboard.shtm");
    }

    @Mapping("/login")
    public ModelAndView login(Context context) {
        // 如果已登录，直接跳转到仪表盘
        if (isLoggedIn(context)) {
            context.redirect("/dashboard");
            return null; // 返回null表示重定向
        }

        ModelAndView mv = new ModelAndView("login.shtm");
        mv.put("pageTitle", "登录 - Nimbus Config");
        mv.put("user", context.session("user"));
        return mv;
    }
    @Mapping("/dashboard")
    public ModelAndView dashboard(Context context) {
        if (!isLoggedIn(context)) {
            context.redirect("/login");
            return null; // 返回null表示重定向
        }
        ModelAndView mv = new ModelAndView("dashboard.shtm");
        mv.put("user", context.session("user"));
        mv.put("pageTitle", "仪表盘 - Nimbus Config");
        return mv;
    }

    @Mapping("/config")
    public ModelAndView config(Context context) {
        if (!isLoggedIn(context)) {
            context.redirect("/login");
            return null; // 返回null表示重定向
        }
        ModelAndView mv = new ModelAndView("config.shtm");
        mv.put("user", context.session("user"));
        mv.put("pageTitle", "配置管理 - Nimbus Config");
        return mv;
    }

    @Mapping("/project")
    public ModelAndView project(Context context) {
        if (!isLoggedIn(context)) {
            context.redirect("/login");
            return null; // 返回null表示重定向
        }
        ModelAndView mv = new ModelAndView("project.shtm");
        mv.put("user", context.session("user"));
        mv.put("pageTitle", "项目管理 - Nimbus Config");
        return mv;
    }

    @Mapping("/env")
    public ModelAndView environment(Context context) {
        if (!isLoggedIn(context)) {
            context.redirect("/login");
            return null; // 返回null表示重定向
        }
        ModelAndView mv = new ModelAndView("environment.shtm");
        mv.put("user", context.session("user"));
        mv.put("pageTitle", "环境管理 - Nimbus Config");
        return mv;
    }

    @Mapping("/user")
    public ModelAndView user(Context context) {
        if (!isLoggedIn(context)) {
            context.redirect("/login");
            return null; // 返回null表示重定向
        }
        ModelAndView mv = new ModelAndView("user.shtm");
        mv.put("user", context.session("user"));
        mv.put("pageTitle", "用户管理 - Nimbus Config");
        return mv;
    }
}