package com.pimeng.plogin.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 密码哈希（JDK 自带，无需额外依赖）。
 * 存储格式：salt(Base64) + hash(Base64) + iterations，每个账号独立盐。
 */
public final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private PasswordHasher() {
    }

    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String saltBase64, int iterations) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    public static boolean verify(String password, String saltBase64, int iterations, String expectedHashBase64) {
        try {
            String actual = hash(password, saltBase64, iterations);
            return MessageDigest.isEqual(
                    Base64.getDecoder().decode(actual),
                    Base64.getDecoder().decode(expectedHashBase64));
        } catch (Exception e) {
            return false;
        }
    }
}
