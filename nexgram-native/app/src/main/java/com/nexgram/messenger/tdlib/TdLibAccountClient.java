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

    private final java.util.concurrent.ConcurrentHashMap<Long, TdApi.Chat> knownChats = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastAppliedProxyFingerprint = null;

    private void initializeClient() {
        Log.i(TAG, "Initializing TDLib client for account: " + accountId);

        Client.ResultHandler updateHandler = object -> {
            if (object instanceof TdApi.UpdateAuthorizationState) {
                handleAuthStateUpdate(((TdApi.UpdateAuthorizationState) object).authorizationState);
            } else if (object instanceof TdApi.UpdateConnectionState) {
                TdApi.ConnectionState state = ((TdApi.UpdateConnectionState) object).state;
                Log.i(TAG, "Account " + accountId + " ConnectionState: " + state.getClass().getSimpleName());
            } else if (object instanceof TdApi.UpdateNewChat) {
                TdApi.Chat chat = ((TdApi.UpdateNewChat) object).chat;
                if (chat != null) knownChats.put(chat.id, chat);
            } else if (object instanceof TdApi.UpdateChatTitle) {
                TdApi.UpdateChatTitle uct = (TdApi.UpdateChatTitle) object;
                TdApi.Chat chat = knownChats.get(uct.chatId);
                if (chat != null) chat.title = uct.title;
            } else if (object instanceof TdApi.UpdateChatPhoto) {
                TdApi.UpdateChatPhoto ucp = (TdApi.UpdateChatPhoto) object;
                TdApi.Chat chat = knownChats.get(ucp.chatId);
                if (chat != null) chat.photo = ucp.photo;
            } else if (object instanceof TdApi.UpdateChatLastMessage) {
                TdApi.UpdateChatLastMessage uclm = (TdApi.UpdateChatLastMessage) object;
                TdApi.Chat chat = knownChats.get(uclm.chatId);
                if (chat != null) {
                    chat.lastMessage = uclm.lastMessage;
                    if (uclm.positions != null) {
                        chat.positions = uclm.positions;
                    }
                }
            } else if (object instanceof TdApi.UpdateChatPosition) {
                TdApi.UpdateChatPosition ucp = (TdApi.UpdateChatPosition) object;
                TdApi.Chat chat = knownChats.get(ucp.chatId);
                if (chat != null) {
                    updateChatPositionInternal(chat, ucp.position);
                }
            } else if (object instanceof TdApi.UpdateChatReadInbox) {
                TdApi.UpdateChatReadInbox ucri = (TdApi.UpdateChatReadInbox) object;
                TdApi.Chat chat = knownChats.get(ucri.chatId);
                if (chat != null) {
                    chat.lastReadInboxMessageId = ucri.lastReadInboxMessageId;
                    chat.unreadCount = ucri.unreadCount;
                }
            } else if (object instanceof TdApi.UpdateChatReadOutbox) {
                TdApi.UpdateChatReadOutbox ucro = (TdApi.UpdateChatReadOutbox) object;
                TdApi.Chat chat = knownChats.get(ucro.chatId);
                if (chat != null) {
                    chat.lastReadOutboxMessageId = ucro.lastReadOutboxMessageId;
                }
            } else if (object instanceof TdApi.UpdateChatNotificationSettings) {
                TdApi.UpdateChatNotificationSettings ucns = (TdApi.UpdateChatNotificationSettings) object;
                TdApi.Chat chat = knownChats.get(ucns.chatId);
                if (chat != null) {
                    chat.notificationSettings = ucns.notificationSettings;
                }
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
            send(new TdApi.SetOption("online", new TdApi.OptionValueBoolean(true)), null);
            send(new TdApi.SetOption("ignore_background_updates", new TdApi.OptionValueBoolean(false)), null);
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
        send(new TdApi.GetChat(chatId), result -> {
            if (result instanceof TdApi.Chat) {
                cacheChat((TdApi.Chat) result);
            }
            if (handler != null) {
                handler.onResult(result);
            }
        });
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
     * Retrieves chat message history from TDLib.
     */
    public void getChatHistory(long chatId, long fromMessageId, int offset, int limit, boolean onlyLocal, Client.ResultHandler handler) {
        send(new TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, onlyLocal), handler);
    }

    /**
     * Deletes messages from a chat.
     */
    public void deleteMessages(long chatId, long[] messageIds, boolean revoke, Client.ResultHandler handler) {
        send(new TdApi.DeleteMessages(chatId, messageIds, revoke), handler);
    }

    private void updateChatPositionInternal(TdApi.Chat chat, TdApi.ChatPosition newPos) {
        if (chat == null || newPos == null) return;
        if (chat.positions == null) {
            chat.positions = new TdApi.ChatPosition[]{newPos};
            return;
        }
        boolean replaced = false;
        java.util.List<TdApi.ChatPosition> list = new java.util.ArrayList<>();
        for (TdApi.ChatPosition pos : chat.positions) {
            if (pos.list.getConstructor() == newPos.list.getConstructor()) {
                if (newPos.order > 0) {
                    list.add(newPos);
                }
                replaced = true;
            } else {
                list.add(pos);
            }
        }
        if (!replaced && newPos.order > 0) {
            list.add(newPos);
        }
        chat.positions = list.toArray(new TdApi.ChatPosition[0]);
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
            String fingerprint = meta.type + ":" + meta.host + ":" + meta.port + ":" + (meta.username != null ? meta.username : "") + ":" + (secret != null ? secret : "");
            if (fingerprint.equals(lastAppliedProxyFingerprint)) {
                if (handler != null) handler.onResult(new TdApi.Ok());
                return;
            }
            lastAppliedProxyFingerprint = fingerprint;
            TdApi.Proxy tdProxy = com.nexgram.messenger.proxy.ProxyController.buildTdApiProxy(meta, secret);
            setProxy(tdProxy, handler);
        } else {
            if ("DIRECT".equals(lastAppliedProxyFingerprint)) {
                if (handler != null) handler.onResult(new TdApi.Ok());
                return;
            }
            lastAppliedProxyFingerprint = "DIRECT";
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

    /**
     * Returns snapshot of all currently known / cached chats.
     */
    public java.util.List<TdApi.Chat> getKnownChats() {
        return new java.util.ArrayList<>(knownChats.values());
    }

    public TdApi.Chat getKnownChat(long chatId) {
        return knownChats.get(chatId);
    }

    public void setOnline(boolean online) {
        send(new TdApi.SetOption("online", new TdApi.OptionValueBoolean(online)), null);
    }

    /**
     * Caches a chat in the known chats map.
     */
    public void cacheChat(TdApi.Chat chat) {
        if (chat != null) {
            knownChats.put(chat.id, chat);
        }
    }

    /**
     * Edits message text via TDLib.
     */
    public void editMessageText(long chatId, long messageId, String newText, Client.ResultHandler handler) {
        TdApi.FormattedText formattedText = new TdApi.FormattedText(newText, new TdApi.TextEntity[0]);
        TdApi.InputMessageText inputContent = new TdApi.InputMessageText(formattedText, null, true);
        send(new TdApi.EditMessageText(chatId, messageId, null, inputContent), handler);
    }

    /**
     * Forwards one or more messages to another chat via TDLib.
     */
    public void forwardMessages(long targetChatId, long fromChatId, long[] messageIds, boolean sendCopy, Client.ResultHandler handler) {
        send(new TdApi.ForwardMessages(targetChatId, null, fromChatId, messageIds, null, sendCopy, false), handler);
    }

    /**
     * Pins a message in a chat.
     */
    public void pinChatMessage(long chatId, long messageId, boolean disableNotification, boolean onlyForSelf, Client.ResultHandler handler) {
        send(new TdApi.PinChatMessage(chatId, messageId, disableNotification, onlyForSelf), handler);
    }

    /**
     * Unpins a message in a chat.
     */
    public void unpinChatMessage(long chatId, long messageId, Client.ResultHandler handler) {
        send(new TdApi.UnpinChatMessage(chatId, messageId), handler);
    }

    /**
     * Queries authoritative TDLib message properties (permissions, editable, deletable, forwardable).
     */
    public void getMessageProperties(long chatId, long messageId, Client.ResultHandler handler) {
        send(new TdApi.GetMessageProperties(chatId, messageId), handler);
    }

    /**
     * Gets the latest pinned message in a chat.
     */
    public void getChatPinnedMessage(long chatId, Client.ResultHandler handler) {
        send(new TdApi.GetChatPinnedMessage(chatId), handler);
    }

    /**
     * Sends a photo message through TDLib.
     */
    public void sendPhoto(long chatId, long replyToMessageId, String filePath, int width, int height, String caption, Client.ResultHandler handler) {
        TdApi.InputFile photoFile = new TdApi.InputFileLocal(filePath);
        TdApi.InputPhoto inputPhoto = new TdApi.InputPhoto(photoFile, null, null, new int[0], width, height);
        TdApi.FormattedText captionFormatted = (caption != null && !caption.isEmpty())
            ? new TdApi.FormattedText(caption, new TdApi.TextEntity[0])
            : null;
        TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto(inputPhoto, captionFormatted, false, null, false);
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Sends a video message through TDLib.
     */
    public void sendVideo(long chatId, long replyToMessageId, String filePath, int duration, int width, int height, String caption, Client.ResultHandler handler) {
        TdApi.InputFile videoFile = new TdApi.InputFileLocal(filePath);
        TdApi.InputVideo inputVideo = new TdApi.InputVideo(videoFile, null, null, 0, new int[0], duration, width, height, true);
        TdApi.FormattedText captionFormatted = (caption != null && !caption.isEmpty())
            ? new TdApi.FormattedText(caption, new TdApi.TextEntity[0])
            : null;
        TdApi.InputMessageVideo content = new TdApi.InputMessageVideo(inputVideo, captionFormatted, false, null, false);
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Sends a document / file through TDLib.
     */
    public void sendDocument(long chatId, long replyToMessageId, String filePath, String caption, Client.ResultHandler handler) {
        TdApi.InputFile docFile = new TdApi.InputFileLocal(filePath);
        TdApi.InputDocument inputDoc = new TdApi.InputDocument(docFile, null, false);
        TdApi.FormattedText captionFormatted = (caption != null && !caption.isEmpty())
            ? new TdApi.FormattedText(caption, new TdApi.TextEntity[0])
            : null;
        TdApi.InputMessageDocument content = new TdApi.InputMessageDocument(inputDoc, captionFormatted);
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Cancels an active or pending TDLib file download.
     */
    public void cancelDownloadFile(int fileId, boolean onlyIfPending, Client.ResultHandler handler) {
        send(new TdApi.CancelDownloadFile(fileId, onlyIfPending), handler);
    }

    /**
     * Sends a voice note message through TDLib.
     */
    public void sendVoiceNote(long chatId, long replyToMessageId, String filePath, int duration, byte[] waveform, String caption, Client.ResultHandler handler) {
        TdApi.InputFile voiceFile = new TdApi.InputFileLocal(filePath);
        TdApi.InputVoiceNote inputVoice = new TdApi.InputVoiceNote(voiceFile, duration, waveform != null ? waveform : new byte[0]);
        TdApi.FormattedText captionFormatted = (caption != null && !caption.isEmpty())
            ? new TdApi.FormattedText(caption, new TdApi.TextEntity[0])
            : null;
        TdApi.InputMessageVoiceNote content = new TdApi.InputMessageVoiceNote(inputVoice, captionFormatted, null);
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Sends a music / audio file through TDLib.
     */
    public void sendAudio(long chatId, long replyToMessageId, String filePath, int duration, String title, String performer, String caption, Client.ResultHandler handler) {
        TdApi.InputFile audioFile = new TdApi.InputFileLocal(filePath);
        TdApi.InputAudio inputAudio = new TdApi.InputAudio(audioFile, null, duration, title, performer);
        TdApi.FormattedText captionFormatted = (caption != null && !caption.isEmpty())
            ? new TdApi.FormattedText(caption, new TdApi.TextEntity[0])
            : null;
        TdApi.InputMessageAudio content = new TdApi.InputMessageAudio(inputAudio, captionFormatted);
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Sends a sticker from installed sticker set.
     */
    public void sendSticker(long chatId, long replyToMessageId, int stickerFileId, int width, int height, String emoji, Client.ResultHandler handler) {
        TdApi.InputFile file = new TdApi.InputFileId(stickerFileId);
        TdApi.InputSticker inputSticker = new TdApi.InputSticker(file, null, width, height);
        TdApi.InputMessageSticker content = new TdApi.InputMessageSticker(inputSticker, emoji != null ? emoji : "");
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Sends an animation / GIF message.
     */
    public void sendAnimation(long chatId, long replyToMessageId, String filePath, int width, int height, int duration, String caption, Client.ResultHandler handler) {
        TdApi.InputFile animFile = new TdApi.InputFileLocal(filePath);
        TdApi.InputAnimation inputAnim = new TdApi.InputAnimation(animFile, null, new int[0], duration, width, height);
        TdApi.FormattedText captionFormatted = (caption != null && !caption.isEmpty())
            ? new TdApi.FormattedText(caption, new TdApi.TextEntity[0])
            : null;
        TdApi.InputMessageAnimation content = new TdApi.InputMessageAnimation(inputAnim, captionFormatted, false, false);
        TdApi.InputMessageReplyTo replyTo = (replyToMessageId > 0)
            ? new TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null)
            : null;
        send(new TdApi.SendMessage(chatId, null, replyTo, null, null, content), handler);
    }

    /**
     * Retrieves installed sticker sets from TDLib.
     */
    public void getInstalledStickerSets(Client.ResultHandler handler) {
        send(new TdApi.GetInstalledStickerSets(new TdApi.StickerTypeRegular()), handler);
    }

    /**
     * Retrieves a full sticker set by set ID.
     */
    public void getStickerSet(long setId, Client.ResultHandler handler) {
        send(new TdApi.GetStickerSet(setId), handler);
    }

    /**
     * Retrieves saved animations / GIFs.
     */
    public void getSavedAnimations(Client.ResultHandler handler) {
        send(new TdApi.GetSavedAnimations(), handler);
    }

    /**
     * Retrieves user details.
     */
    public void getUser(long userId, Client.ResultHandler handler) {
        send(new TdApi.GetUser(userId), handler);
    }
}
