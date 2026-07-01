package cloud.xuantong.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 加密/解密工具（配置值加密存储用）
 * <p>
 * 密钥从 core.yml 的 config.encryptKey 读取，不配置则不加密
 */
public class AesEncryptor {
    private static final Logger log = LoggerFactory.getLogger(AesEncryptor.class);
    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";

    private final SecretKeySpec key;

    public AesEncryptor(String keyStr) {
        if (keyStr == null || keyStr.isEmpty()) {
            this.key = null;
        } else {
            // 使用密钥的 SHA-256 哈希作为 AES 密钥（保证 32 字节）
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
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return "ENC(" + Base64.getEncoder().encodeToString(encrypted) + ")";
        } catch (Exception e) {
            log.error("Encryption failed", e);
            return plainText;
        }
    }

    public String decrypt(String cipherText) {
        if (key == null || cipherText == null) return cipherText;
        try {
            if (!cipherText.startsWith("ENC(") || !cipherText.endsWith(")")) {
                return cipherText; // 非加密格式，原样返回
            }
            String b64 = cipherText.substring(4, cipherText.length() - 1);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(b64));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
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
