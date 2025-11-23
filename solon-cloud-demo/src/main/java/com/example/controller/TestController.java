package com.example.controller;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
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
    private String test = "default-value";

    @Inject("${solon.cloud.xxx.server:default-server}")
    private String test2;
    @Mapping("/")
    public Result<String> test() {
        return Result.succeed(this.test);
    }
}
