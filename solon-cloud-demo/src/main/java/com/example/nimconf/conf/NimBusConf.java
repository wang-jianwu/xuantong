//package com.example.nimconf.conf;
//
//import com.nimbus.client.ConfigClientFactory;
//import com.nimbus.client.NimBusClient;
//import org.noear.solon.annotation.Configuration;
//import org.noear.solon.annotation.Destroy;
//import org.noear.solon.annotation.Init;
//
//import java.util.Collections;
//import java.util.Map;
//import java.util.concurrent.CompletableFuture;
//
///**
// * author wangjianwu
// * date 2025/11/16 21:11
// */
//@Configuration
//public class NimBusConf {
//
//
//    @Init
//    public void init() {
//        ConfigClientFactory.init(Collections.singletonList("127.0.0.1:8088"), "demo", "dev");
//        CompletableFuture.runAsync(() -> {
//            try {
//                Thread.sleep(20000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//            try {
//                Map s = NimBusClient.getObject("demo.aaa", Map.class);
//                System.out.println("xxxxxxxxxx=" + s);
//            }catch (Exception ex){
//                ex.printStackTrace();
//            }
//        });
//    }
//
//    @Destroy
//    public void destroy() {
//        ConfigClientFactory.closeAll();
//    }
//}
