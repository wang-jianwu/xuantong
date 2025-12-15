package com.example.controller;

import cloud.xuantong.client.XuantongConfig;
import cloud.xuantong.client.annotation.ConfigValue;
import cloud.xuantong.client.enums.ValueType;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * author 封于修
 * date 2025/11/19 00:21
 */
@Controller
public class TestController {
    private static final Logger logger = LoggerFactory.getLogger(TestController.class);

    @ConfigValue(value = "demo.nimbus.aaa", defaultValue = "xxx", type = ValueType.STRING)
    private String test;

    public TestController() {
        logger.info("TestController 实例化完成");
    }

//    @Inject("${solon.cloud.xxx.server:default-server}")
//    private String test2;

    @Mapping("/")
    public Result<String> test() {
        logger.info("test() 方法被调用，当前 test 字段值: {}", this.test);
        return Result.succeed(this.test);
    }
}
