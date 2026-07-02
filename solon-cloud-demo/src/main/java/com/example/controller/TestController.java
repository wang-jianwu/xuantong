package com.example.controller;

import cloud.xuantong.client.annotation.ConfigValue;
import lombok.Getter;
import lombok.ToString;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
//import org.noear.solon.cloud.annotation.CloudConfig;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * author 封于修
 * date 2025/11/19 00:21
 */
@Controller
public class TestController {
    private static final Logger logger = LoggerFactory.getLogger(TestController.class);

    @ConfigValue(value = "demo.aaa", defaultValue = "xxx")
    //@CloudConfig(value = "demo.aaa", autoRefreshed = true)
    private String string;

    /**
     * json [{"name":"德莱厄斯","age":18},{"name":"锐雯","age":18}]
     */
    @ConfigValue(value = "demo.list")
    //@CloudConfig(value = "demo.list", autoRefreshed = true)
    private List<User> list;

    /**
     * json {"MALE":[{"name":"德莱厄斯","age":18}],"FEMALE":[{"name":"锐雯","age":18}]}
     */
    @ConfigValue(value = "demo.map", required = true)
    private Map<Gender, List<User>> map;

//    @CloudConfig(value = "demo.map", autoRefreshed = true)
//    private Map<String, List<User>> map;

    @Mapping("/")
    public Result<String> test() {
        String value = this.string + "\n"
                + this.list + "\n"
                + this.map.get(Gender.MALE);
//                + this.map.get(Gender.MALE.name());
        logger.info("test() 方法被调用，当前 test 字段值: {}", value);
        return Result.succeed(value);
    }

    @Getter
    @ToString
    static class User {
        private String name;
        private int age;
    }

    @Getter
    enum Gender {
        MALE, FEMALE
    }
}
