package cloud.xuantong.client.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * author 封于修
 * date 2025/12/11 23:28
 * 加密配置注解
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptedConfig {
    /**
     * 加密算法
     */
    String algorithm() default "AES";

    /**
     * 密钥ID（从密钥管理服务获取）
     */
    String keyId() default "";
}
