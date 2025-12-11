package cloud.xuantong.client.annotation;

import cloud.xuantong.client.enums.ConfigSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * author 封于修
 * date 2025/12/11 23:13
 * 类配置注解
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigClass {
    /**
     * 配置前缀
     */
    String prefix() default "";

    /**
     * 配置源
     */
    ConfigSource source() default ConfigSource.REMOTE;

    /**
     * 自动刷新间隔（秒）
     */
    int refreshInterval() default 300;
}
