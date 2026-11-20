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

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        Log.i(TAG, "Initializing NexGram Native Client...");
        
        // 1. Initialize hardware-backed session vault via Android Keystore
        sessionVault = new SecureSessionVault(this);

        // 2. Initialize native multi-account manager
        accountManager = new AccountManager(this, sessionVault);

        // 3. Initialize proxy controller
        proxyController = new ProxyController(this);

        Log.i(TAG, "NexGram Native Client initialized successfully. Active accounts: " + accountManager.getAccountCount());
    }

    public static NexGramApplication getInstance() {
        return instance;
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
