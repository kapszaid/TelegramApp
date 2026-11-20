package com.nexgram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import com.nexgram.messenger.auth.SecureSessionVault;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.Before;
import org.junit.Test;

/**
 * TdLibIntegrationTest
 *
 * Verifies that TDLib 1.8.70 AAR classes (Client, TdApi) are resolved and accessible,
 * constructors and parameters are valid, and multi-account database directory
 * and Keystore encryption key isolation are strictly enforced.
 */
public class TdLibIntegrationTest {

    private static class MockSharedPreferences implements SharedPreferences {
        private final Map<String, Object> storage = new HashMap<>();

        @Override public Map<String, ?> getAll() { return storage; }
        @Override public String getString(String key, String defValue) { return (String) storage.getOrDefault(key, defValue); }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return null; }
        @Override public int getInt(String key, int defValue) { return (Integer) storage.getOrDefault(key, defValue); }
        @Override public long getLong(String key, long defValue) { return (Long) storage.getOrDefault(key, defValue); }
        @Override public float getFloat(String key, float defValue) { return 0; }
        @Override public boolean getBoolean(String key, boolean defValue) { return (Boolean) storage.getOrDefault(key, defValue); }
        @Override public boolean contains(String key) { return storage.containsKey(key); }
        @Override public Editor edit() { return new MockEditor(storage); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private static class MockEditor implements Editor {
            private final Map<String, Object> target;
            private final Map<String, Object> changes = new HashMap<>();

            MockEditor(Map<String, Object> target) { this.target = target; }
            @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { return this; }
            @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { return this; }
            @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            @Override public Editor remove(String key) { changes.put(key, null); return this; }
            @Override public Editor clear() { changes.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                for (Map.Entry<String, Object> e : changes.entrySet()) {
                    if (e.getValue() == null) target.remove(e.getKey());
                    else target.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    private SecureSessionVault vault;

    @Before
    public void setUp() {
        vault = new SecureSessionVault(new MockSharedPreferences(), false);
    }

    @Test
    public void testTdLibClassesResolvedFromAar() {
        // Verify Client.class and TdApi.class are resolved from the packaged classes.jar inside tdlib-android.aar
        assertNotNull(Client.class);
        assertNotNull(TdApi.class);
        assertNotNull(TdApi.SetTdlibParameters.class);
        assertNotNull(TdApi.SetDatabaseEncryptionKey.class);
        assertNotNull(TdApi.UpdateAuthorizationState.class);
    }

    @Test
    public void testSetTdlibParametersConstructor() {
        byte[] dbKey = vault.getOrCreateDatabaseKey("account_101");
        assertNotNull(dbKey);
        assertEquals(32, dbKey.length);

        TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
        params.useTestDc = false;
        params.databaseDirectory = "/data/data/com.nexgram.messenger/files/tdlib_accounts/account_101";
        params.filesDirectory = "/data/data/com.nexgram.messenger/files/tdlib_accounts/account_101/files";
        params.databaseEncryptionKey = dbKey;
        params.useFileDatabase = true;
        params.useChatInfoDatabase = true;
        params.useMessageDatabase = true;
        params.useSecretChats = true;
        params.apiId = 12345;
        params.apiHash = "dummy_test_hash";
        params.systemLanguageCode = "en";
        params.deviceModel = "Test Device";
        params.systemVersion = "Android 14";
        params.applicationVersion = "1.0.0";

        assertEquals(TdApi.SetTdlibParameters.CONSTRUCTOR, params.getConstructor());
        assertEquals(32, params.databaseEncryptionKey.length);
        assertEquals("/data/data/com.nexgram.messenger/files/tdlib_accounts/account_101", params.databaseDirectory);
    }

    @Test
    public void testSetDatabaseEncryptionKeyConstructor() {
        byte[] keyBytes = new byte[32];
        keyBytes[0] = 0x42;
        TdApi.SetDatabaseEncryptionKey setKey = new TdApi.SetDatabaseEncryptionKey(keyBytes);

        assertEquals(TdApi.SetDatabaseEncryptionKey.CONSTRUCTOR, setKey.getConstructor());
        assertNotNull(setKey.newEncryptionKey);
        assertEquals(32, setKey.newEncryptionKey.length);
        assertEquals(0x42, setKey.newEncryptionKey[0]);
    }

    @Test
    public void testMultiAccountIsolation() {
        // Verify distinct accounts receive distinct, independent 32-byte encryption keys
        byte[] keyAlice = vault.getOrCreateDatabaseKey("alice_991");
        byte[] keyBob = vault.getOrCreateDatabaseKey("bob_992");

        assertNotNull(keyAlice);
        assertNotNull(keyBob);
        assertEquals(32, keyAlice.length);
        assertEquals(32, keyBob.length);

        // Keys must be cryptographically independent (never equal across different accounts)
        boolean identical = true;
        for (int i = 0; i < 32; i++) {
            if (keyAlice[i] != keyBob[i]) {
                identical = false;
                break;
            }
        }
        assertFalse("Keys for different accounts must never be identical", identical);

        // Verify independent directory naming structure
        File rootDir = new File("/data/data/com.nexgram.messenger/files/tdlib_accounts");
        File dirAlice = new File(rootDir, "alice_991");
        File dirBob = new File(rootDir, "bob_992");

        assertNotEquals(dirAlice.getAbsolutePath(), dirBob.getAbsolutePath());
        assertTrue(dirAlice.getAbsolutePath().endsWith("alice_991"));
        assertTrue(dirBob.getAbsolutePath().endsWith("bob_992"));
    }

    @Test
    public void testAuthenticationFunctionsConstructors() {
        // 1. SetAuthenticationPhoneNumber
        TdApi.PhoneNumberAuthenticationSettings settings = new TdApi.PhoneNumberAuthenticationSettings();
        TdApi.SetAuthenticationPhoneNumber setPhone = new TdApi.SetAuthenticationPhoneNumber("+1234567890", settings);
        assertEquals(TdApi.SetAuthenticationPhoneNumber.CONSTRUCTOR, setPhone.getConstructor());
        assertEquals("+1234567890", setPhone.phoneNumber);
        assertNotNull(setPhone.settings);

        // 2. CheckAuthenticationCode
        TdApi.CheckAuthenticationCode checkCode = new TdApi.CheckAuthenticationCode("54321");
        assertEquals(TdApi.CheckAuthenticationCode.CONSTRUCTOR, checkCode.getConstructor());
        assertEquals("54321", checkCode.code);

        // 3. CheckAuthenticationPassword
        TdApi.CheckAuthenticationPassword checkPassword = new TdApi.CheckAuthenticationPassword("secret_2fa_pwd");
        assertEquals(TdApi.CheckAuthenticationPassword.CONSTRUCTOR, checkPassword.getConstructor());
        assertEquals("secret_2fa_pwd", checkPassword.password);

        // 4. RegisterUser
        TdApi.RegisterUser register = new TdApi.RegisterUser("Alice", "Smith", false);
        assertEquals(TdApi.RegisterUser.CONSTRUCTOR, register.getConstructor());
        assertEquals("Alice", register.firstName);
        assertEquals("Smith", register.lastName);
        assertFalse(register.disableNotification);

        // 5. ResendAuthenticationCode
        TdApi.ResendAuthenticationCode resend = new TdApi.ResendAuthenticationCode();
        assertEquals(TdApi.ResendAuthenticationCode.CONSTRUCTOR, resend.getConstructor());

        // 6. LogOut
        TdApi.LogOut logOut = new TdApi.LogOut();
        assertEquals(TdApi.LogOut.CONSTRUCTOR, logOut.getConstructor());

        // 7. GetMe
        TdApi.GetMe getMe = new TdApi.GetMe();
        assertEquals(TdApi.GetMe.CONSTRUCTOR, getMe.getConstructor());
    }

    @Test
    public void testAllAuthorizationStatesResolved() {
        // Verify all states required by Milestone 2.3 are resolvable from TDLib AAR classes
        assertNotNull(TdApi.AuthorizationStateWaitTdlibParameters.class);
        assertNotNull(TdApi.AuthorizationStateWaitPhoneNumber.class);
        assertNotNull(TdApi.AuthorizationStateWaitCode.class);
        assertNotNull(TdApi.AuthorizationStateWaitPassword.class);
        assertNotNull(TdApi.AuthorizationStateWaitRegistration.class);
        assertNotNull(TdApi.AuthorizationStateWaitEmailAddress.class);
        assertNotNull(TdApi.AuthorizationStateWaitEmailCode.class);
        assertNotNull(TdApi.AuthorizationStateWaitOtherDeviceConfirmation.class);
        assertNotNull(TdApi.AuthorizationStateReady.class);
        assertNotNull(TdApi.AuthorizationStateLoggingOut.class);
        assertNotNull(TdApi.AuthorizationStateClosing.class);
        assertNotNull(TdApi.AuthorizationStateClosed.class);
    }

    @Test
    public void testDatabaseKeyPersistenceAcrossAppRestart() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault initialVault = new SecureSessionVault(mockPrefs, true);

        // Simulate first run: generate 32-byte database encryption key
        byte[] keyFirstRun = initialVault.getOrCreateDatabaseKey("account_main");
        assertNotNull(keyFirstRun);
        assertEquals(32, keyFirstRun.length);

        // Simulate application restart: new Vault instance loaded against existing storage
        SecureSessionVault restartedVault = new SecureSessionVault(mockPrefs, true);
        byte[] keyRestart = restartedVault.getOrCreateDatabaseKey("account_main");
        assertNotNull(keyRestart);
        assertEquals(32, keyRestart.length);

        // Keys MUST be 100% byte-for-byte identical so TDLib can decrypt existing database
        org.junit.Assert.assertArrayEquals("Database encryption key must persist across restarts", keyFirstRun, keyRestart);
    }

    @Test
    public void testSessionRevocationAndCleanup() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);

        testVault.saveSession("account_main", "mock_auth_key_material");
        assertTrue(testVault.hasSession("account_main"));
        assertEquals("mock_auth_key_material", testVault.getSession("account_main"));

        // Simulate session revocation / logout cleanup
        testVault.removeSession("account_main");
        assertFalse(testVault.hasSession("account_main"));
    }

    @Test
    public void testChatClassesAndFunctionsConstructors() {
        // LoadChats
        TdApi.LoadChats loadChats = new TdApi.LoadChats(new TdApi.ChatListMain(), 30);
        assertEquals(TdApi.LoadChats.CONSTRUCTOR, loadChats.getConstructor());
        assertEquals(30, loadChats.limit);
        assertTrue(loadChats.chatList instanceof TdApi.ChatListMain);

        // GetChat
        TdApi.GetChat getChat = new TdApi.GetChat(987654321L);
        assertEquals(TdApi.GetChat.CONSTRUCTOR, getChat.getConstructor());
        assertEquals(987654321L, getChat.chatId);

        // SearchChats
        TdApi.SearchChats searchChats = new TdApi.SearchChats("telegram", null, 25);
        assertEquals(TdApi.SearchChats.CONSTRUCTOR, searchChats.getConstructor());
        assertEquals("telegram", searchChats.query);
        assertEquals(25, searchChats.limit);

        // DownloadFile
        TdApi.DownloadFile download = new TdApi.DownloadFile(404, 2, 0, 0, false);
        assertEquals(TdApi.DownloadFile.CONSTRUCTOR, download.getConstructor());
        assertEquals(404, download.fileId);
        assertEquals(2, download.priority);
    }

    @Test
    public void testTelegramAvatarHelper() {
        // 7 distinct official Telegram avatar colors
        assertEquals(7, com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.AVATAR_COLORS.length);

        int color0 = com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getAvatarColor(0);
        int color1 = com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getAvatarColor(1);
        int color7 = com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getAvatarColor(7);
        assertEquals(color0, color7); // Modulo 7 wrap-around
        assertNotEquals(color0, color1);

        // Initials extraction
        assertEquals("AS", com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getInitials("Alice Smith"));
        assertEquals("NE", com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getInitials("NexGram"));
        assertEquals("TG", com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getInitials(""));
        assertEquals("TG", com.nexgram.messenger.ui.helpers.TelegramAvatarHelper.getInitials(null));
    }

    @Test
    public void testTelegramMessageFormatter() {
        // Text Message
        TdApi.FormattedText formattedText = new TdApi.FormattedText("Hello Telegram!", new TdApi.TextEntity[0]);
        TdApi.MessageText messageText = new TdApi.MessageText(formattedText, null, null);
        TdApi.Message msg = new TdApi.Message();
        msg.content = messageText;
        assertEquals("Hello Telegram!", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(msg));

        // Photo Message without caption
        TdApi.MessagePhoto messagePhoto = new TdApi.MessagePhoto(null, null, null, false, false, false);
        msg.content = messagePhoto;
        assertEquals("📷 Photo", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(msg));

        // Photo Message with caption
        TdApi.FormattedText caption = new TdApi.FormattedText("Sunset view", new TdApi.TextEntity[0]);
        TdApi.MessagePhoto photoWithCaption = new TdApi.MessagePhoto(null, null, caption, false, false, false);
        msg.content = photoWithCaption;
        assertEquals("📷 Sunset view", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(msg));

        // Voice Message
        msg.content = new TdApi.MessageVoiceNote();
        assertEquals("🎤 Voice message", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(msg));
    }

    @Test
    public void testTdLibProxyClassesAndFunctions() {
        // 1. SOCKS5 Proxy Type Constructor
        TdApi.ProxyTypeSocks5 socksType = new TdApi.ProxyTypeSocks5("tg_user", "tg_pass_123");
        assertEquals(TdApi.ProxyTypeSocks5.CONSTRUCTOR, socksType.getConstructor());
        assertEquals("tg_user", socksType.username);
        assertEquals("tg_pass_123", socksType.password);

        // 2. MTProto Proxy Type Constructor
        TdApi.ProxyTypeMtproto mtType = new TdApi.ProxyTypeMtproto("secret_key_hex_123456789");
        assertEquals(TdApi.ProxyTypeMtproto.CONSTRUCTOR, mtType.getConstructor());
        assertEquals("secret_key_hex_123456789", mtType.secret);

        // 3. TdApi.Proxy Constructor
        TdApi.Proxy proxy = new TdApi.Proxy("198.51.100.1", 1080, socksType);
        assertEquals(TdApi.Proxy.CONSTRUCTOR, proxy.getConstructor());
        assertEquals("198.51.100.1", proxy.server);
        assertEquals(1080, proxy.port);
        assertEquals(socksType, proxy.type);

        // 4. AddProxy Function
        TdApi.AddProxy addProxy = new TdApi.AddProxy(proxy, true, "NexGram-Test");
        assertEquals(TdApi.AddProxy.CONSTRUCTOR, addProxy.getConstructor());
        assertEquals(proxy, addProxy.proxy);
        assertTrue(addProxy.enable);
        assertEquals("NexGram-Test", addProxy.comment);

        // 5. EnableProxy Function
        TdApi.EnableProxy enableProxy = new TdApi.EnableProxy(42);
        assertEquals(TdApi.EnableProxy.CONSTRUCTOR, enableProxy.getConstructor());
        assertEquals(42, enableProxy.proxyId);

        // 6. DisableProxy Function (Direct Connection)
        TdApi.DisableProxy disableProxy = new TdApi.DisableProxy();
        assertEquals(TdApi.DisableProxy.CONSTRUCTOR, disableProxy.getConstructor());

        // 7. RemoveProxy Function
        TdApi.RemoveProxy removeProxy = new TdApi.RemoveProxy(42);
        assertEquals(TdApi.RemoveProxy.CONSTRUCTOR, removeProxy.getConstructor());
        assertEquals(42, removeProxy.proxyId);

        // 8. PingProxy Function
        TdApi.PingProxy pingProxy = new TdApi.PingProxy(proxy);
        assertEquals(TdApi.PingProxy.CONSTRUCTOR, pingProxy.getConstructor());
        assertEquals(proxy, pingProxy.proxy);

        // 9. GetProxies Function
        TdApi.GetProxies getProxies = new TdApi.GetProxies();
        assertEquals(TdApi.GetProxies.CONSTRUCTOR, getProxies.getConstructor());

        // 10. ConnectionStateConnectingToProxy
        TdApi.ConnectionStateConnectingToProxy connToProxy = new TdApi.ConnectionStateConnectingToProxy();
        assertEquals(TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR, connToProxy.getConstructor());
    }

    @Test
    public void testMessageDeduplicationAndSendStateSuccess() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        // 1. Initial pending outgoing message (oldMessageId = -1001)
        TdApi.Message pendingMsg = new TdApi.Message();
        pendingMsg.id = -1001L;
        pendingMsg.isOutgoing = true;
        pendingMsg.date = 1700000000;
        pendingMsg.sendingState = new TdApi.MessageSendingStatePending();
        TdApi.FormattedText text = new TdApi.FormattedText("Hello", new TdApi.TextEntity[0]);
        pendingMsg.content = new TdApi.MessageText(text, null, null);

        adapter.appendOrUpdateMessage(pendingMsg);
        // Expect 2 items: 1 DateHeader + 1 MessageItem
        assertEquals(2, adapter.getItemCount());

        // 2. Duplicate arrival of same pending message MUST NOT add duplicate bubble
        adapter.appendOrUpdateMessage(pendingMsg);
        assertEquals("Duplicate message must be deduplicated in place", 2, adapter.getItemCount());

        // 3. UpdateMessageSendSucceeded event arrives: oldMessageId replaced by permanent server ID 5555L, sendingState becomes null
        TdApi.Message serverMsg = new TdApi.Message();
        serverMsg.id = 5555L;
        serverMsg.isOutgoing = true;
        serverMsg.date = 1700000000;
        serverMsg.sendingState = null; // server confirmed
        serverMsg.content = new TdApi.MessageText(text, null, null);

        adapter.onMessageSendSucceeded(-1001L, serverMsg);
        assertEquals("Item count must remain unchanged after send success", 2, adapter.getItemCount());

        // 4. Another UpdateNewMessage with serverMsg MUST NOT duplicate
        adapter.appendOrUpdateMessage(serverMsg);
        assertEquals("Server confirmed message must not duplicate", 2, adapter.getItemCount());
    }
}
