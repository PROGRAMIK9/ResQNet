package com.example.myapplication;

import android.util.Base64;
import android.util.Log;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class CryptoUtils {
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    // 32-byte key for AES-256
    private static final String AES_KEY = "12345678901234567890123456789012"; 
    private static final String AES_IV = "1234567890123456";

    public static String encrypt(String value) {
        if (value == null) return null;
        try {
            IvParameterSpec iv = new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec skeySpec = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv);

            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception ex) {
            Log.e("CryptoUtils", "Encryption failed: " + ex.getMessage());
        }
        return null;
    }

    public static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return null;
        try {
            IvParameterSpec iv = new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec skeySpec = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

            byte[] original = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
            return new String(original, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            Log.e("CryptoUtils", "Decryption failed: " + ex.getMessage());
        }
        return null;
    }
}
