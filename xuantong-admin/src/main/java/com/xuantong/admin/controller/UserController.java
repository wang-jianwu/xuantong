package com.xuantong.admin.controller;

import com.xuantong.core.model.User;
import com.xuantong.core.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.List;

@Controller
@Mapping("/api/user")
@Api(produces = "用户管理接口")
public class UserController {
    @Inject
    private UserService userService;

    @ApiOperation(value = "获取所有用户")
    @Get
    @Mapping
    public Result<List<User>> getAllUsers() {
        List<User> users = userService.getAllUsers();
        return Result.succeed(users);
    }

    @ApiOperation(value = "根据ID获取用户")
    @Get
    @Mapping("/{id}")
    public Result<User> getUserById(
            @ApiParam(value = "用户ID") @Path Long id) {
        User user = userService.getUserById(id);
        return user != null ? Result.succeed(user) : Result.failure("用户不存在");
    }

    @ApiOperation(value = "创建用户")
    @Post
    @Mapping
    public Result<String> createUser(
            @ApiParam(value = "用户对象") @Body User user) {
        try {
            boolean success = userService.createUser(user);
            return success ? Result.succeed("创建成功") : Result.failure("创建失败");
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage());
        }
    }

    @ApiOperation(value = "更新用户")
    @Put
    @Mapping
    public Result<String> updateUser(
            @ApiParam(value = "用户对象") @Body User user) {
        boolean success = userService.updateUser(user);
        return success ? Result.succeed("更新成功") : Result.failure("更新失败");
    }

    @ApiOperation(value = "设置用户激活状态")
    @Put
    @Mapping("/active/{userId}/{active}")
    public Result<String> setUserActive(
            @ApiParam(value = "用户ID") @Path Long userId,
            @ApiParam(value = "激活状态") @Path boolean active) {
        boolean success = userService.setUserActive(userId, active);
        return success ? Result.succeed("操作成功") : Result.failure("操作失败");
    }

    @ApiOperation(value = "删除用户")
    @Delete
    @Mapping("/{id}")
    public Result<String> deleteUser(@ApiParam(value = "用户ID") @Path Long id) {
        boolean success = userService.deleteUser(id);
        return success ? Result.succeed("删除成功") : Result.failure("删除失败");
    }
}