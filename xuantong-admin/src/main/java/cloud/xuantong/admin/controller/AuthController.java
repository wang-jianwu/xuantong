package cloud.xuantong.admin.controller;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.service.UserService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

@Controller
@Mapping("/api/auth")
public class AuthController {
    @Inject
    private UserService userService;

    @Post
    @Mapping("/login")
    public Result<User> login(
            @Param String username,
            @Param String password, Context ctx) {
        User user = userService.authenticate(username, password);
        if (user != null) {
            ctx.sessionSet("user", user);
            return Result.succeed(user);
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
    public Result<User> getUserInfo() {
        User user = Context.current().session("user", User.class);
        return user != null ? Result.succeed(user) : Result.failure("未登录");
    }
}