package cloud.xuantong.security;

import cloud.xuantong.security.util.AesEncryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AesEncryptor 加解密测试
 */
class AesEncryptorTest {

    private static final String KEY = "my-secret-key-for-test-1234567890";

    @Test
    @DisplayName("加解密对称性：encrypt → decrypt 还原原文")
    void encryptDecrypt_roundtrip() {
        AesEncryptor encryptor = new AesEncryptor(KEY);
        String plain = "Hello, 玄同!";

        String cipher = encryptor.encrypt(plain);
        assertNotNull(cipher);
        assertNotEquals(plain, cipher);
        assertTrue(cipher.startsWith("ENC("));
        assertTrue(cipher.endsWith(")"));

        String decrypted = encryptor.decrypt(cipher);
        assertEquals(plain, decrypted);
    }

    @Test
    @DisplayName("加解密对称性：空字符串")
    void encryptDecrypt_emptyString() {
        AesEncryptor encryptor = new AesEncryptor(KEY);
        String plain = "";

        String cipher = encryptor.encrypt(plain);
        assertEquals(plain, encryptor.decrypt(cipher));
    }

    @Test
    @DisplayName("isEnabled：有密钥时为 true")
    void isEnabled_withKey() {
        assertTrue(new AesEncryptor(KEY).isEnabled());
    }

    @Test
    @DisplayName("isEnabled：空密钥时为 false")
    void isEnabled_emptyKey() {
        assertFalse(new AesEncryptor("").isEnabled());
    }

    @Test
    @DisplayName("isEnabled：null 密钥时为 false")
    void isEnabled_nullKey() {
        assertFalse(new AesEncryptor(null).isEnabled());
    }

    @Test
    @DisplayName("encrypt：无密钥时原样返回")
    void encrypt_noKey_returnsOriginal() {
        AesEncryptor encryptor = new AesEncryptor("");
        assertEquals("hello", encryptor.encrypt("hello"));
    }

    @Test
    @DisplayName("decrypt：非 ENC() 格式原样返回（不解密）")
    void decrypt_nonEncFormat_returnsOriginal() {
        AesEncryptor encryptor = new AesEncryptor(KEY);
        String plain = "plain-text-value";
        assertEquals(plain, encryptor.decrypt(plain));
    }

    @Test
    @DisplayName("decrypt：null 输入返回 null")
    void decrypt_nullInput() {
        AesEncryptor encryptor = new AesEncryptor(KEY);
        assertNull(encryptor.decrypt(null));
    }

    @Test
    @DisplayName("encrypt：null 输入返回 null")
    void encrypt_nullInput() {
        AesEncryptor encryptor = new AesEncryptor(KEY);
        assertNull(encryptor.encrypt(null));
    }

    @Test
    @DisplayName("不同密钥加密结果不同（安全性验证）")
    void differentKeys_produceDifferentCiphers() {
        AesEncryptor e1 = new AesEncryptor("key-one-aaaaaaaaaaaaaa");
        AesEncryptor e2 = new AesEncryptor("key-two-bbbbbbbbbbbbbb");
        String plain = "same-value";

        String c1 = e1.encrypt(plain);
        String c2 = e2.encrypt(plain);

        assertNotEquals(c1, c2);
        assertEquals(plain, e1.decrypt(c1));
        assertEquals(plain, e2.decrypt(c2));
    }

    @Test
    @DisplayName("用错误密钥解密：返回密文（不抛异常）")
    void decrypt_wrongKey_returnsOriginal() {
        AesEncryptor e1 = new AesEncryptor("correct-key-aaaaaaaaaaaa");
        AesEncryptor e2 = new AesEncryptor("wrong-key-bbbbbbbbbbbbbb");

        String cipher = e1.encrypt("secret-data");
        String result = e2.decrypt(cipher);
        assertEquals(cipher, result);
    }
}
