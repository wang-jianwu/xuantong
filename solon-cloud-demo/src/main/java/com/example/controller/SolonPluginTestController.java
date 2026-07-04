//package com.example.controller;
//
//import cloud.xuantong.client.annotation.ConfigValue;
//import lombok.Getter;
//import lombok.ToString;
//import org.noear.solon.annotation.Controller;
//import org.noear.solon.annotation.Mapping;
//import org.noear.solon.core.handle.Result;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.List;
//import java.util.Map;
//
///**
// * author 封于修
// * date 2026/7/5 01:48
// */
//@Controller
//public class SolonPluginTestController {
//    private static final Logger logger = LoggerFactory.getLogger(SolonPluginTestController.class);
//
//    @ConfigValue(value = "demo.aaa", defaultValue = "xxx")
//    private String string;
//
//    /**
//     * json [{"name":"德莱厄斯","age":18},{"name":"锐雯","age":18}]
//     */
//    @ConfigValue(value = "demo.list")
//    private List<SolonCloudPluginTestController.User> list;
//
//    /**
//     * json {"MALE":[{"name":"德莱厄斯","age":18}],"FEMALE":[{"name":"锐雯","age":18}]}
//     */
//    @ConfigValue(value = "demo.map", required = true)
//    private Map<Gender, List<User>> map;
//
//    @Mapping("/")
//    public Result<String> test() {
//        String value = this.string + "\n"
//                + this.list + "\n"
//                + this.map.get(Gender.MALE);
//        logger.info("test() 方法被调用，当前 test 字段值: {}", value);
//        return Result.succeed(value);
//    }
//
//    @Getter
//    @ToString
//    static class User {
//        private String name;
//        private int age;
//    }
//
//    @Getter
//    enum Gender {
//        MALE, FEMALE
//    }
//}
