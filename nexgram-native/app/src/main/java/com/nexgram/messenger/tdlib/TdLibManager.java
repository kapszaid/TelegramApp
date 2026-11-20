package com.nexgram.messenger.tdlib;

import android.content.Context;
import android.util.Log;
import com.nexgram.messenger.auth.SecureSessionVault;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * TdLibManager
 *
 * Central repository and coordinator for all native TDLib account clients.
 * Enforces multi-account client isolation and safe asynchronous lifecycle.
 */
public class TdLibManager {
    private static final String TAG = "TdLibManager";
    private static volatile TdLibManager instance;

    private final Context appContext;
    private final SecureSessionVault sessionVault;
    private final ConcurrentHashMap<String, TdLibAccountClient> activeClients = new ConcurrentHashMap<>();

    private TdLibManager(Context context, SecureSessionVault sessionVault) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (sessionVault == null) {
            throw new IllegalArgumentException("SecureSessionVault cannot be null");
        }
        this.appContext = context.getApplicationContext();
        this.sessionVault = sessionVault;

        configureGlobalLogVerbosity(1); // 1 = errors only
    }

    public static synchronized void init(Context context, SecureSessionVault sessionVault) {
        if (instance == null) {
            instance = new TdLibManager(context, sessionVault);
            Log.i(TAG, "TdLibManager initialized successfully.");
        }
    }

    public static TdLibManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("TdLibManager is not initialized. Call init() first.");
        }
        return instance;
    }

    /**
     * Configures the native TDLib logging verbosity level synchronously.
     * Value 0 = fatal, 1 = errors, 2 = warnings, 3 = info, 4 = debug.
     */
    public void configureGlobalLogVerbosity(int verbosityLevel) {
        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(verbosityLevel));
            Log.i(TAG, "TDLib global log verbosity set to: " + verbosityLevel);
        } catch (Exception e) {
            Log.w(TAG, "Could not set TDLib log verbosity: " + e.getMessage());
        }
    }

    /**
     * Retrieves or creates an isolated TDLib client for the specified account ID.
     */
    public synchronized TdLibAccountClient getOrCreateClient(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }

        TdLibAccountClient existing = activeClients.get(accountId);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        TdLibAccountClient newClient = new TdLibAccountClient(appContext, accountId, sessionVault);
        activeClients.put(accountId, newClient);
        return newClient;
    }

    public TdLibAccountClient getClient(String accountId) {
        if (accountId == null) return null;
        return activeClients.get(accountId);
    }

    public synchronized void closeClient(String accountId) {
        if (accountId == null) return;
        TdLibAccountClient client = activeClients.remove(accountId);
        if (client != null) {
            client.close();
        }
    }

    public synchronized void closeAllClients() {
        Log.i(TAG, "Closing all active TDLib clients (" + activeClients.size() + " active)");
        for (Map.Entry<String, TdLibAccountClient> entry : activeClients.entrySet()) {
            entry.getValue().close();
        }
        activeClients.clear();
    }

    public int getActiveClientCount() {
        return activeClients.size();
    }

    public Map<String, TdLibAccountClient> getActiveClients() {
        return Collections.unmodifiableMap(activeClients);
    }
}
