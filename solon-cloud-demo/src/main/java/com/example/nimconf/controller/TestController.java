package com.example.nimconf.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.cloud.annotation.CloudConfig;
import org.noear.solon.core.handle.Result;

/**
 * author wangjianwu
 * date 2025/11/19 00:21
 */
@Controller
public class TestController {

    @CloudConfig(value = "demo.nimbus.aaa", autoRefreshed = true)
    private String test;

    @Mapping("/")
    public Result<String> test() {
        return Result.succeed(this.test);
    }
}
