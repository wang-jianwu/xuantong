//package com.example.nimconf.conf;
//
//import cloud.xuantong.client.XuantongConfig;
//import org.noear.solon.annotation.*;
//
//import java.util.Collections;
//
///**
// * author 封于修
// * date 2025/11/16 21:11
// */
//@Component(index = -1000)
//public class XuantongConf {
//
//
//    @Bean
//    public void init() {
//        XuantongConfig.initWithAnnotations(Collections.singletonList("223.109.140.78:8088"), Collections.singletonList("demo"), "dev", "com.example.*");
//    }
//
//    @Destroy
//    public void destroy() {
//        XuantongConfig.closeAll();
//    }
//}
