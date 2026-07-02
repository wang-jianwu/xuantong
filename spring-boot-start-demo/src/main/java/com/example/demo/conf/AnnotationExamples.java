package com.example.demo.conf;

import cloud.xuantong.client.annotation.ConfigValue;
import lombok.Data;
import lombok.Getter;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @ConfigValue 注解使用示例类
 * <p>
 * 说明：@ConfigValue 注解功能强大，支持基本类型、复杂对象、JSON等多种格式，
 * 无需额外注解即可满足所有配置需求。
 */
@Configuration
public class AnnotationExamples {

    /**
     * 示例1：基本类型配置
     */
    @Component
    @Getter
    public static class BasicConfigService {

        @ConfigValue(value = "demo.nimbus.aaa", defaultValue = "MyApp", autoRefresh = true)
        private String appName;

        @ConfigValue(value = "social.trtc.appid", defaultValue = "8080")
        private Long appid;

        @ConfigValue(value = "social.audit.switch", defaultValue = "true")
        private boolean featureEnabled;
    }

    /**
     * 示例2：复杂对象配置（JSON格式）
     */
    @Component
    @Getter
    public static class ComplexConfigService {

        @ConfigValue(value = "social.app.conf")
        private List<AppConfig> appConfig;

        @ConfigValue(value = "social.pay.channel.zl", autoRefresh = true)
        private Map<PayChannel, PaymentConfig> paymentConfig;
    }

    // 示例配置对象类
    @Getter
    public static class AppConfig {
        private String appId;
        private String appName;

    }

    @Getter
    public static class PaymentConfig {
        private String appId;
        private String secret;
        private String mchId;
        private String serNo;
        private String prekey;
        private String pulkey;
        private String notifyUrl;
    }

    @Getter
    public enum PayChannel {
        alipay, wxpay
    }
}