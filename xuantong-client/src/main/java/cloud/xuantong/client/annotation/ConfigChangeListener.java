package cloud.xuantong.client.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * author 封于修
 * date 2025/12/11 23:16
 * 监听器注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigChangeListener {
    /**
     * 监听的配置键（支持通配符）
     */
    String[] keys();

    /**
     * 是否异步执行
     */
    boolean async() default true;

    /**
     * 执行顺序
     */
    int order() default 0;
}
