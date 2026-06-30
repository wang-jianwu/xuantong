package cloud.xuantong.admin.controller;

import cloud.xuantong.core.model.User;
import cloud.xuantong.core.service.UserService;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/user")
public class UserController {
    @Inject
    private UserService userService;

    @Get
    @Mapping
    public Result<List<User>> getAllUsers() {
        List<User> users = userService.getAllUsers();
        return Result.succeed(users);
    }

    @Get
    @Mapping("/{id}")
    public Result<User> getUserById(
            @Path Long id) {
        User user = userService.getUserById(id);
        return user != null ? Result.succeed(user) : Result.failure("用户不存在");
    }

    @Post
    @Mapping
    public Result<String> createUser(
            @Body User user) {
        try {
            boolean success = userService.createUser(user);
            return success ? Result.succeed("创建成功") : Result.failure("创建失败");
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Put
    @Mapping
    public Result<String> updateUser(
            @Body User user) {
        boolean success = userService.updateUser(user);
        return success ? Result.succeed("更新成功") : Result.failure("更新失败");
    }

    @Put
    @Mapping("/active/{userId}/{active}")
    public Result<String> setUserActive(
            @Path Long userId,
            @Path boolean active) {
        boolean success = userService.setUserActive(userId, active);
        return success ? Result.succeed("操作成功") : Result.failure("操作失败");
    }

    @Delete
    @Mapping("/{id}")
    public Result<String> deleteUser(@Path Long id) {
        boolean success = userService.deleteUser(id);
        return success ? Result.succeed("删除成功") : Result.failure("删除失败");
    }
}