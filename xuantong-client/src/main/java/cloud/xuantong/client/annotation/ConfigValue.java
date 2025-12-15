package cloud.xuantong.client.annotation;

/**
 * author 封于修
 * date 2025/12/10 22:59
 * 核心注解
 */

import cloud.xuantong.client.enums.ValueType;

import java.lang.annotation.*;


@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigValue {

    /**
     * 配置键前缀（用于从复合配置中提取特定部分）
     */
    String prefix() default "";

    /**
     * 配置键
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
     * 数据类型
     */
    ValueType type() default ValueType.STRING;

    /**
     * 配置描述
     */
    String description() default "";
}
