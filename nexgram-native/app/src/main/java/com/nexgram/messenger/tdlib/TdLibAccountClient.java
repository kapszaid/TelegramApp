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

    public interface RawUpdateListener {
        void onUpdate(TdApi.Object update);
    }

    private final String accountId;
    private final File databaseDir;
    private final File filesDir;
    private final SecureSessionVault sessionVault;
    private final CopyOnWriteArrayList<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawUpdateListener> rawUpdateListeners = new CopyOnWriteArrayList<>();
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
            } else if (object instanceof TdApi.UpdateConnectionState) {
                TdApi.ConnectionState state = ((TdApi.UpdateConnectionState) object).state;
                Log.i(TAG, "Account " + accountId + " ConnectionState: " + state.getClass().getSimpleName());
            }
            for (RawUpdateListener listener : rawUpdateListeners) {
                try {
                    listener.onUpdate(object);
                } catch (Exception e) {
                    Log.e(TAG, "Error in RawUpdateListener: " + e.getMessage());
                }
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
        String stateName = authState != null ? authState.getClass().getSimpleName() : "null";
        Log.i(TAG, "Account " + accountId + " AuthState updated: " + stateName);

        if (authState instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            sendTdlibParameters();
        } else if (authState instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            Log.i(TAG, "Account " + accountId + ": Waiting for phone number input.");
        } else if (authState instanceof TdApi.AuthorizationStateWaitCode) {
            Log.i(TAG, "Account " + accountId + ": Waiting for OTP verification code.");
        } else if (authState instanceof TdApi.AuthorizationStateWaitPassword) {
            Log.i(TAG, "Account " + accountId + ": Waiting for 2FA password.");
        } else if (authState instanceof TdApi.AuthorizationStateWaitRegistration) {
            Log.i(TAG, "Account " + accountId + ": Waiting for profile registration (new user).");
        } else if (authState instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            Log.i(TAG, "Account " + accountId + ": Waiting for authentication email address.");
        } else if (authState instanceof TdApi.AuthorizationStateWaitEmailCode) {
            Log.i(TAG, "Account " + accountId + ": Waiting for authentication email code.");
        } else if (authState instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            Log.i(TAG, "Account " + accountId + ": Waiting for other device QR confirmation.");
        } else if (authState instanceof TdApi.AuthorizationStateReady) {
            Log.i(TAG, "Account " + accountId + ": Authorization complete. Client ready.");
        } else if (authState instanceof TdApi.AuthorizationStateLoggingOut) {
            Log.i(TAG, "Account " + accountId + ": Client is logging out from Telegram servers.");
        } else if (authState instanceof TdApi.AuthorizationStateClosing) {
            Log.i(TAG, "Account " + accountId + ": Client connection closing.");
        } else if (authState instanceof TdApi.AuthorizationStateClosed) {
            Log.i(TAG, "Account " + accountId + ": Client connection closed.");
            isClosed.set(true);
        } else {
            Log.w(TAG, "Account " + accountId + ": Unhandled or unexpected state: " + (authState != null ? authState.getClass().getName() : "null"));
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
                // Apply saved account-specific proxy (if configured)
                com.nexgram.messenger.NexGramApplication app = com.nexgram.messenger.NexGramApplication.getInstance();
                if (app != null && app.getProxyController() != null) {
                    applySavedProxy(app.getProxyController(), null);
                }
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

    public void addRawUpdateListener(RawUpdateListener listener) {
        if (listener != null && !rawUpdateListeners.contains(listener)) {
            rawUpdateListeners.add(listener);
        }
    }

    public void removeRawUpdateListener(RawUpdateListener listener) {
        rawUpdateListeners.remove(listener);
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

    /**
     * Submits phone number to Telegram MTProto server to initiate authorization.
     */
    public void setAuthenticationPhoneNumber(String phoneNumber, Client.ResultHandler handler) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            if (handler != null) handler.onResult(new TdApi.Error(400, "Phone number cannot be empty"));
            return;
        }
        TdApi.PhoneNumberAuthenticationSettings settings = new TdApi.PhoneNumberAuthenticationSettings();
        send(new TdApi.SetAuthenticationPhoneNumber(phoneNumber.trim(), settings), handler);
    }

    /**
     * Submits OTP verification code received via Telegram app or SMS.
     * The code is passed directly to native TDLib in memory and never logged or persisted.
     */
    public void checkAuthenticationCode(String code, Client.ResultHandler handler) {
        if (code == null || code.trim().isEmpty()) {
            if (handler != null) handler.onResult(new TdApi.Error(400, "Verification code cannot be empty"));
            return;
        }
        send(new TdApi.CheckAuthenticationCode(code.trim()), handler);
    }

    /**
     * Submits Two-Step Verification (2FA) cloud password.
     * The password is encrypted in native TDLib via SRP (Secure Remote Password) protocol
     * and never logged or stored in plaintext.
     */
    public void checkAuthenticationPassword(String password, Client.ResultHandler handler) {
        if (password == null) {
            if (handler != null) handler.onResult(new TdApi.Error(400, "Password cannot be null"));
            return;
        }
        send(new TdApi.CheckAuthenticationPassword(password), handler);
    }

    /**
     * Registers a new Telegram user (first and last name) if account does not exist.
     */
    public void registerUser(String firstName, String lastName, Client.ResultHandler handler) {
        if (firstName == null || firstName.trim().isEmpty()) {
            if (handler != null) handler.onResult(new TdApi.Error(400, "First name cannot be empty"));
            return;
        }
        send(new TdApi.RegisterUser(firstName.trim(), lastName != null ? lastName.trim() : "", false), handler);
    }

    /**
     * Requests resending the authentication code.
     */
    public void resendAuthenticationCode(Client.ResultHandler handler) {
        send(new TdApi.ResendAuthenticationCode(), handler);
    }

    /**
     * Logs out the user from Telegram MTProto servers and revokes this session.
     */
    public void logOut(Client.ResultHandler handler) {
        send(new TdApi.LogOut(), handler);
    }

    /**
     * Queries current authorized user information (GetMe).
     */
    public void getMe(Client.ResultHandler handler) {
        send(new TdApi.GetMe(), handler);
    }

    /**
     * Deletes local database files upon explicit account logout.
     */
    public void deleteDatabaseFiles() {
        deleteRecursively(databaseDir);
        deleteRecursively(filesDir);
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    /**
     * Loads more chats from local database or Telegram servers into memory.
     */
    public void loadChats(TdApi.ChatList chatList, int limit, Client.ResultHandler handler) {
        send(new TdApi.LoadChats(chatList != null ? chatList : new TdApi.ChatListMain(), limit), handler);
    }

    /**
     * Retrieves a Chat object by its unique 64-bit ID.
     */
    public void getChat(long chatId, Client.ResultHandler handler) {
        send(new TdApi.GetChat(chatId), handler);
    }

    /**
     * Searches chats using TDLib native search engine.
     */
    public void searchChats(String query, int limit, Client.ResultHandler handler) {
        send(new TdApi.SearchChats(query, null, limit), handler);
    }

    /**
     * Downloads an avatar or media file by its TDLib file ID.
     */
    public void downloadFile(int fileId, int priority, Client.ResultHandler handler) {
        send(new TdApi.DownloadFile(fileId, priority, 0, 0, false), handler);
    }

    /**
     * Applies the configured proxy from ProxyController for this account, or disables proxy if none/disabled.
     */
    public void applySavedProxy(com.nexgram.messenger.proxy.ProxyController proxyController, Client.ResultHandler handler) {
        if (proxyController == null) {
            if (handler != null) handler.onResult(new TdApi.Ok());
            return;
        }
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata meta = proxyController.getProxyMetadata(accountId);
        if (meta != null && meta.enabled && meta.type != com.nexgram.messenger.proxy.ProxyController.ProxyType.DIRECT) {
            String secret = proxyController.getProxySecret(accountId);
            TdApi.Proxy tdProxy = com.nexgram.messenger.proxy.ProxyController.buildTdApiProxy(meta, secret);
            setProxy(tdProxy, handler);
        } else {
            disableProxy(handler);
        }
    }

    /**
     * Sets or switches the proxy for this account client via TDLib AddProxy(enable=true).
     * If proxy is null, disables proxy (direct connection).
     * Note: Changing proxies on an authorized client DOES NOT disconnect the session or require OTP.
     */
    public void setProxy(TdApi.Proxy proxy, Client.ResultHandler handler) {
        if (proxy == null) {
            disableProxy(handler);
            return;
        }
        send(new TdApi.AddProxy(proxy, true, "NexGram-" + accountId), result -> {
            if (result instanceof TdApi.AddedProxy) {
                TdApi.AddedProxy added = (TdApi.AddedProxy) result;
                Log.i(TAG, "Account " + accountId + ": TDLib Proxy added and enabled with ID: " + added.id);
            } else if (result instanceof TdApi.Error) {
                Log.e(TAG, "Account " + accountId + ": TDLib AddProxy failed: " + ((TdApi.Error) result).message);
            }
            if (handler != null) {
                handler.onResult(result);
            }
        });
    }

    /**
     * Disables any active proxy and reverts this client to Direct Connection.
     */
    public void disableProxy(Client.ResultHandler handler) {
        send(new TdApi.DisableProxy(), result -> {
            Log.i(TAG, "Account " + accountId + ": TDLib Proxy disabled. Direct connection active.");
            if (handler != null) {
                handler.onResult(result);
            }
        });
    }

    /**
     * Pings a proxy configuration using TDLib's native network stack.
     * Returns TdApi.Seconds (latency) or TdApi.Error.
     */
    public void pingProxy(TdApi.Proxy proxy, Client.ResultHandler handler) {
        if (proxy == null) {
            if (handler != null) handler.onResult(new TdApi.Error(400, "Proxy cannot be null"));
            return;
        }
        send(new TdApi.PingProxy(proxy), handler);
    }

    /**
     * Queries all added proxies for this account client.
     */
    public void getProxies(Client.ResultHandler handler) {
        send(new TdApi.GetProxies(), handler);
    }
}
