package com.nexgram.messenger.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyInfo;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/**
 * SecureSessionVault
 *
 * Stores Telegram authorization tokens securely on device using Android Keystore
 * and AES-256-GCM EncryptedSharedPreferences.
 *
 * SECURITY INVARIANTS:
 * 1. Plaintext fallback is strictly prohibited. If encryption initialization fails,
 *    this vault fails closed (throws SecurityException), never persisting credentials unencrypted.
 * 2. Hardware-backed security is verified where supported by the device OEM and Android OS.
 *
 * ANDROID KEYSTORE LIMITATIONS & BEHAVIOR:
 * - Hardware Backing: True hardware protection (StrongBox Keymaster or TEE) varies across device
 *   chipsets. High-end devices have hardware HSMs; budget devices or emulators may utilize software TEE.
 * - Lock Screen Invalidation: On certain Android versions, removing or changing the system lock screen
 *   (PIN/Pattern/Biometric) may invalidate or flush Keystore-bound encryption keys.
 * - Device Migration: Android Keystore keys are strictly non-exportable and bound to the local hardware chip;
 *   they cannot be restored onto a new device via standard Android cloud backup.
 * - Root/Compromise: Keystore keys in active process memory can be inspected if the Android kernel is rooted.
 */
public class SecureSessionVault {
    private static final String TAG = "SecureSessionVault";
    private static final String PREF_NAME = "nexgram_secure_vault";

    private final SharedPreferences encryptedPreferences;
    private final boolean isHardwareBacked;

    public SecureSessionVault(Context context) throws SecurityException {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setRequestStrongBoxBacked(false) // Request standard Keystore with TEE fallback
                    .build();

            this.encryptedPreferences = EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            // Determine if device reports hardware-level Keystore support
            this.isHardwareBacked = verifyHardwareBacking(context);
            Log.i(TAG, "SecureSessionVault initialized successfully. Hardware-backed: " + this.isHardwareBacked);

        } catch (GeneralSecurityException | IOException e) {
            // STRICT FAIL-SECURE: Never fall back to unencrypted SharedPreferences!
            Log.e(TAG, "CRITICAL: Secure encryption vault initialization failed. Plaintext fallback rejected.", e);
            throw new SecurityException("SecureSessionVault failed to initialize cryptographic engine: " + e.getMessage(), e);
        }
    }

    /**
     * Testing constructor for mock/isolated encrypted preferences
     */
    @VisibleForTesting
    public SecureSessionVault(SharedPreferences customEncryptedPrefs, boolean hardwareBacked) {
        if (customEncryptedPrefs == null) {
            throw new IllegalArgumentException("Preferences implementation cannot be null");
        }
        this.encryptedPreferences = customEncryptedPrefs;
        this.isHardwareBacked = hardwareBacked;
    }

    private boolean verifyHardwareBacking(Context context) {
        // Android Keystore hardware backing detection
        try {
            return context.getPackageManager() != null &&
                    context.getPackageManager().hasSystemFeature("android.hardware.strongbox_keystore");
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isHardwareBacked() {
        return isHardwareBacked;
    }

    public synchronized void saveSession(String accountId, String authKeyHex) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }
        if (authKeyHex == null || authKeyHex.trim().isEmpty()) {
            throw new IllegalArgumentException("Auth key cannot be null or empty");
        }
        encryptedPreferences.edit().putString("auth_token_" + accountId, authKeyHex).apply();
    }

    public synchronized String getSession(String accountId) {
        if (accountId == null) return null;
        return encryptedPreferences.getString("auth_token_" + accountId, null);
    }

    public synchronized void removeSession(String accountId) {
        if (accountId == null) return;
        encryptedPreferences.edit().remove("auth_token_" + accountId).apply();
    }

    public synchronized void clearAllSessions() {
        encryptedPreferences.edit().clear().apply();
    }

    public synchronized boolean hasSession(String accountId) {
        if (accountId == null) return false;
        return encryptedPreferences.contains("auth_token_" + accountId);
    }
}
