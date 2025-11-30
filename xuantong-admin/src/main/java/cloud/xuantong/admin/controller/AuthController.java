package cloud.xuantong.admin.controller;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

@Controller
@Mapping("/api/auth")
@Api(produces = "认证管理接口")
public class AuthController {
    @Inject
    private UserService userService;

    @ApiOperation(value = "用户登录")
    @Post
    @Mapping("/login")
    public Result<User> login(
            @ApiParam(value = "用户名") @Param String username,
            @ApiParam(value = "密码") @Param String password, Context ctx) {
        User user = userService.authenticate(username, password);
        if (user != null) {
            ctx.sessionSet("user", user);
            return Result.succeed(user);
        }
        return Result.failure("用户名或密码错误");
    }

    @ApiOperation(value = "用户退出")
    @Get
    @Mapping("/logout")
    public Result<String> logout() {
        Context.current().sessionClear();
        return Result.succeed("退出成功");
    }

    @ApiOperation(value = "获取用户信息")
    @Get
    @Mapping("/userinfo")
    public Result<User> getUserInfo() {
        User user = Context.current().session("user", User.class);
        return user != null ? Result.succeed(user) : Result.failure("未登录");
    }
}