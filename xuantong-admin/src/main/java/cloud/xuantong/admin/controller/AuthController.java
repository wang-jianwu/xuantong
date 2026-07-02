package cloud.xuantong.admin.controller;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.service.UserService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.HashMap;
import java.util.Map;

@Controller
@Mapping("/api/auth")
public class AuthController {
    @Inject
    private UserService userService;

    @Post
    @Mapping("/login")
    public Result<Map<String, Object>> login(
            @Param String username,
            @Param String password, Context ctx) {
        User user = userService.authenticate(username, password);
        if (user != null) {
            ctx.sessionSet("user", user);
            return Result.succeed(toSafeMap(user));
        }
        return Result.failure("用户名或密码错误");
    }

    @Get
    @Mapping("/logout")
    public Result<String> logout() {
        Context.current().sessionClear();
        return Result.succeed("退出成功");
    }

    @Get
    @Mapping("/userinfo")
    public Result<Map<String, Object>> getUserInfo() {
        User user = Context.current().session("user", User.class);
        return user != null ? Result.succeed(toSafeMap(user)) : Result.failure("未登录");
    }

    /**
     * 过滤密码哈希，防止 User 序列化时泄露密码字段
     */
    private Map<String, Object> toSafeMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("email", user.getEmail());
        map.put("realName", user.getRealName());
        map.put("role", user.getRole());
        map.put("isActive", user.getIsActive());
        map.put("lastLoginTime", user.getLastLoginTime());
        return map;
    }
}