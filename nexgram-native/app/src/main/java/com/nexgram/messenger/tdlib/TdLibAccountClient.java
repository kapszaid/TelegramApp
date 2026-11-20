package com.nexgram.messenger.tdlib;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.nexgram.messenger.BuildConfig;
import com.nexgram.messenger.auth.SecureSessionVault;
import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * TdLibAccountClient
 *
 * Encapsulates a dedicated, isolated TDLib Client instance for a single NexGram account.
 *
 * SECURITY & ARCHITECTURAL INVARIANTS:
 * 1. Independent Client: Every account owns its own distinct native TDLib Client instance.
 * 2. Isolated Storage: Every account has a dedicated database and files directory on disk.
 * 3. Keystore-Encrypted Storage: A cryptographically secure 32-byte key is retrieved from
 *    SecureSessionVault (Android Keystore MasterKey AES-256-GCM) and passed to TDLib via SetTdlibParameters.
 *    Plaintext keys are never stored in SharedPreferences, logs, or plain files.
 * 4. Asynchronous State Machine: Incoming TDLib authorization states are parsed and exposed
 *    via thread-safe observers without blocking the UI thread.
 */
public class TdLibAccountClient {
    private static final String TAG = "TdLibAccountClient";

    public interface StateListener {
        void onAuthorizationStateChanged(TdApi.AuthorizationState newState);
        void onClientError(int errorCode, String errorMessage);
    }

    private final String accountId;
    private final File databaseDir;
    private final File filesDir;
    private final SecureSessionVault sessionVault;
    private final CopyOnWriteArrayList<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private Client client;
    private volatile TdApi.AuthorizationState currentAuthState;

    public TdLibAccountClient(Context context, String accountId, SecureSessionVault sessionVault) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }
        if (sessionVault == null) {
            throw new IllegalArgumentException("SecureSessionVault cannot be null");
        }

        this.accountId = accountId;
        this.sessionVault = sessionVault;

        // Dedicated per-account database directories
        File baseDir = new File(context.getFilesDir(), "tdlib_accounts");
        this.databaseDir = new File(baseDir, accountId);
        this.filesDir = new File(baseDir, accountId + "/files");

        if (!this.databaseDir.exists()) {
            this.databaseDir.mkdirs();
        }
        if (!this.filesDir.exists()) {
            this.filesDir.mkdirs();
        }

        initializeClient();
    }

    private void initializeClient() {
        Log.i(TAG, "Initializing TDLib client for account: " + accountId);

        Client.ResultHandler updateHandler = object -> {
            if (object instanceof TdApi.UpdateAuthorizationState) {
                handleAuthStateUpdate(((TdApi.UpdateAuthorizationState) object).authorizationState);
            }
        };

        Client.ExceptionHandler exceptionHandler = e -> {
            Log.e(TAG, "TDLib uncaught exception on account " + accountId + ": " + e.getMessage(), e);
            notifyError(-1, e.getMessage());
        };

        // Client.create launches background native event loop and auto-loads libtdjni.so via static initializer
        this.client = Client.create(updateHandler, exceptionHandler, null);
    }

    private void handleAuthStateUpdate(TdApi.AuthorizationState authState) {
        this.currentAuthState = authState;
        Log.i(TAG, "Account " + accountId + " AuthState: " + authState.getClass().getSimpleName());

        if (authState instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            sendTdlibParameters();
        } else if (authState instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            Log.i(TAG, "Account " + accountId + " is ready for phone number authentication.");
        } else if (authState instanceof TdApi.AuthorizationStateReady) {
            Log.i(TAG, "Account " + accountId + " authorization completed. TDLib engine online.");
        } else if (authState instanceof TdApi.AuthorizationStateClosed) {
            Log.i(TAG, "Account " + accountId + " client closed.");
            isClosed.set(true);
        }

        notifyAuthStateChanged(authState);
    }

    private void sendTdlibParameters() {
        // Retrieve or generate hardware-protected 32-byte encryption key from Keystore vault
        byte[] dbKey = sessionVault.getOrCreateDatabaseKey(accountId);

        TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
        params.useTestDc = false;
        params.databaseDirectory = databaseDir.getAbsolutePath();
        params.filesDirectory = filesDir.getAbsolutePath();
        params.databaseEncryptionKey = dbKey;
        params.useFileDatabase = true;
        params.useChatInfoDatabase = true;
        params.useMessageDatabase = true;
        params.useSecretChats = true;
        params.apiId = BuildConfig.TELEGRAM_APP_ID;
        params.apiHash = BuildConfig.TELEGRAM_APP_HASH;
        params.systemLanguageCode = java.util.Locale.getDefault().getLanguage();
        params.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        params.systemVersion = "Android " + Build.VERSION.RELEASE;
        params.applicationVersion = BuildConfig.VERSION_NAME;

        send(params, result -> {
            if (result instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error) result;
                Log.e(TAG, "Failed to set TDLib parameters for " + accountId + ": [" + error.code + "] " + error.message);
                notifyError(error.code, error.message);
            } else {
                Log.i(TAG, "TDLib parameters accepted for account " + accountId + ". Database encrypted with Keystore key.");
            }
        });
    }

    public void send(TdApi.Function query, Client.ResultHandler handler) {
        if (isClosed.get() || client == null) {
            Log.w(TAG, "Cannot send query. Client is closed for account: " + accountId);
            return;
        }
        client.send(query, handler);
    }

    public static TdApi.Object execute(TdApi.Function query) throws Client.ExecutionException {
        return Client.execute(query);
    }

    public void addStateListener(StateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
            if (currentAuthState != null) {
                listener.onAuthorizationStateChanged(currentAuthState);
            }
        }
    }

    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    private void notifyAuthStateChanged(TdApi.AuthorizationState state) {
        for (StateListener listener : stateListeners) {
            try {
                listener.onAuthorizationStateChanged(state);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying state listener: " + e.getMessage());
            }
        }
    }

    private void notifyError(int code, String message) {
        for (StateListener listener : stateListeners) {
            try {
                listener.onClientError(code, message);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying error listener: " + e.getMessage());
            }
        }
    }

    public synchronized void close() {
        if (isClosed.compareAndSet(false, true)) {
            Log.i(TAG, "Closing TDLib client for account: " + accountId);
            if (client != null) {
                client.send(new TdApi.Close(), result -> {
                    Log.i(TAG, "Close request finished for account " + accountId);
                });
            }
        }
    }

    public String getAccountId() {
        return accountId;
    }

    public File getDatabaseDirectory() {
        return databaseDir;
    }

    public TdApi.AuthorizationState getCurrentAuthState() {
        return currentAuthState;
    }

    public boolean isClosed() {
        return isClosed.get();
    }
}
