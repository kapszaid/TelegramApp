package com.nexgram.messenger;

import android.app.Application;
import android.util.Log;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.auth.SecureSessionVault;
import com.nexgram.messenger.proxy.ProxyController;

/**
 * NexGram Application Root
 * Manages native lifecycle, hardware-backed session vault, and multi-account state.
 */
public class NexGramApplication extends Application {
    private static final String TAG = "NexGramApplication";
    private static NexGramApplication instance;

    private SecureSessionVault sessionVault;
    private AccountManager accountManager;
    private ProxyController proxyController;
    private boolean vaultAvailable = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        Log.i(TAG, "Initializing NexGram Native Client...");

        try {
            // 1. Initialize hardware-backed session vault via Android Keystore
            sessionVault = new SecureSessionVault(this);
            vaultAvailable = true;
        } catch (SecurityException e) {
            Log.e(TAG, "FATAL: Cryptographic session vault could not be initialized. Plaintext fallback rejected.", e);
            vaultAvailable = false;
        }

        // 2. Initialize native multi-account manager (safe even if vault failed)
        accountManager = new AccountManager(this, sessionVault);

        // 3. Initialize proxy controller with secure credential support
        proxyController = new ProxyController(this, sessionVault);

        Log.i(TAG, "NexGram Native Client initialized. Vault Available: " + vaultAvailable +
                   ", Active accounts: " + accountManager.getAccountCount());
    }

    public static NexGramApplication getInstance() {
        return instance;
    }

    public boolean isVaultAvailable() {
        return vaultAvailable;
    }

    public SecureSessionVault getSessionVault() {
        return sessionVault;
    }

    public AccountManager getAccountManager() {
        return accountManager;
    }

    public ProxyController getProxyController() {
        return proxyController;
    }
}
