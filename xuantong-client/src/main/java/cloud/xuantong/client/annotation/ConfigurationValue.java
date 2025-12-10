package cloud.xuantong.client.annotation;

/**
 * author 封于修
 * date 2025/12/10 22:59
 */

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface ConfigurationValue {

    /**
     * 配置项的key
     * 配置项的名称，用于在配置文件中查找对应的值。
     *
     * @return 配置项的key字符串。这是必需的，查找和识别特定配置项的关键。
     */
    String key();

    /**
     * 配置项的默认值，当配置文件中不存在该配置时使用此默认值。
     *
     * @return 默认值字符串。如果未指定，默认为空字符串""。
     */
    String defaultValue() default "";

    /**
     * 是否为 JSON 类型
     * 设置为 true 时，值将通过 JSON 反序列化处理
     */
    boolean json() default false;
}
