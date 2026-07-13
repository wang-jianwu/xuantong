package cloud.xuantong.client.annotation;

/**
 * author 封于修
 * date 2025/12/10 22:59
 * 核心注解
 */

import java.lang.annotation.*;


@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigValue {

    /**
     * 配置 dataId
     */
    String value() default "";

    /**
     * 默认值
     */
    String defaultValue() default "";

    /**
     * 是否必须
     */
    boolean required() default false;

    /**
     * 自动刷新（动态配置）
     */
    boolean autoRefresh() default true;

    /**
     * 配置描述
     */
    String description() default "";
}
