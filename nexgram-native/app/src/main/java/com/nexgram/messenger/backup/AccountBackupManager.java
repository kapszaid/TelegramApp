package com.nexgram.messenger.backup;

import java.util.Base64;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Account Backup and Restore Engine
 * Uses password-derived AES-256-GCM encryption for secure, portable backups.
 * Zero plaintext session strings; zero clipboard dumping.
 */
public class AccountBackupManager {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;

    public static class BackupPayload {
        public int version = 1;
        public long exportedAt;
        public String accountName;
        public String phone;
        public String username;
        public String accountId;
        public String encryptedAuthToken;
        public String proxySettingsJson;
    }

    private final Gson gson = new Gson();

    private SecretKey deriveKey(char[] password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    /**
     * Create an AES-256-GCM encrypted backup package
     */
    public String createEncryptedBackup(BackupPayload payload, String password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        SecretKey secretKey = deriveKey(password.toCharArray(), salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        String plainJson = gson.toJson(payload);
        byte[] cipherText = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));

        // Format: [Base64(salt)]:[Base64(iv)]:[Base64(ciphertext)]
        return Base64.getEncoder().encodeToString(salt) + ":" +
               Base64.getEncoder().encodeToString(iv) + ":" +
               Base64.getEncoder().encodeToString(cipherText);
    }

    /**
     * Restore and validate an encrypted backup package
     */
    public BackupPayload restoreEncryptedBackup(String encryptedData, String password) throws Exception {
        String[] parts = encryptedData.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid backup archive format");
        }

        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] iv = Base64.getDecoder().decode(parts[1]);
        byte[] cipherText = Base64.getDecoder().decode(parts[2]);

        SecretKey secretKey = deriveKey(password.toCharArray(), salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] plainBytes = cipher.doFinal(cipherText);
        String plainJson = new String(plainBytes, StandardCharsets.UTF_8);

        return gson.fromJson(plainJson, BackupPayload.class);
    }
}
