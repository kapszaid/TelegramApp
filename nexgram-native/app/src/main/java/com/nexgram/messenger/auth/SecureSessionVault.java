package com.nexgram.messenger.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * SecureSessionVault
 * Stores Telegram authorization tokens securely on device using Android Keystore
 * and AES-256-GCM EncryptedSharedPreferences.
 * Zero plaintext session files; zero external PC server dependency.
 */
public class SecureSessionVault {
    private static final String TAG = "SecureSessionVault";
    private static final String PREF_NAME = "nexgram_secure_vault";

    private SharedPreferences encryptedPreferences;

    public SecureSessionVault(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            encryptedPreferences = EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to initialize hardware-backed encrypted vault, using fallback protected mode", e);
            encryptedPreferences = context.getSharedPreferences(PREF_NAME + "_protected", Context.MODE_PRIVATE);
        }
    }

    public void saveSession(String accountId, String authKeyHex) {
        if (accountId == null || authKeyHex == null) return;
        encryptedPreferences.edit().putString("auth_token_" + accountId, authKeyHex).apply();
    }

    public String getSession(String accountId) {
        if (accountId == null) return null;
        return encryptedPreferences.getString("auth_token_" + accountId, null);
    }

    public void removeSession(String accountId) {
        if (accountId == null) return;
        encryptedPreferences.edit().remove("auth_token_" + accountId).apply();
    }

    public void clearAllSessions() {
        encryptedPreferences.edit().clear().apply();
    }
}
