package cloud.xuantong.security.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加密/解密工具（配置值加密存储用）
 * <p>
 * 使用 AES/GCM/NoPadding（认证加密模式）：
 * - 每次加密生成随机 12 字节 IV（避免相同明文产生相同密文）
 * - GCM 模式自带完整性校验（防止篡改）
 * - 密文格式：ENC(Base64(IV + Ciphertext + Tag))
 * <p>
 * 密钥从 core.yml 的 config.encryptKey 读取，不配置则不加密
 */
public class AesEncryptor {
    private static final Logger log = LoggerFactory.getLogger(AesEncryptor.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;  // GCM 推荐 12 字节 IV
    private static final int TAG_LENGTH = 128; // 128-bit 认证标签

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesEncryptor(String keyStr) {
        if (keyStr == null || keyStr.isEmpty()) {
            this.key = null;
        } else {
            byte[] hash = sha256(keyStr);
            this.key = new SecretKeySpec(hash, "AES");
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plainText) {
        if (key == null || plainText == null) return plainText;
        try {
            // 生成随机 IV
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + 密文（含 GCM Tag）
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return "ENC(" + Base64.getEncoder().encodeToString(combined) + ")";
        } catch (Exception e) {
            log.error("Encryption failed", e);
            // 标记为加密的配置绝不能在异常时降级为明文落库。
            throw new IllegalStateException("Failed to encrypt configuration value", e);
        }
    }

    public String decrypt(String cipherText) {
        if (key == null || cipherText == null) return cipherText;
        try {
            if (!cipherText.startsWith("ENC(") || !cipherText.endsWith(")")) {
                return cipherText;
            }
            String b64 = cipherText.substring(4, cipherText.length() - 1);
            byte[] combined = Base64.getDecoder().decode(b64);

            if (combined.length < IV_LENGTH + 1) {
                log.warn("Invalid encrypted data length");
                return cipherText;
            }

            // 提取 IV 和密文
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Decryption failed: encrypted value or key is invalid");
            return cipherText;
        }
    }

    private byte[] sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
