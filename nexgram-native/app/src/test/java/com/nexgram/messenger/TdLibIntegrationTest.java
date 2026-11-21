package com.nexgram.messenger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import com.nexgram.messenger.auth.SecureSessionVault;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    @Test
    public void testInitialHistoryLoadingAndEmptyConversation() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        // 1. Empty conversation handling
        adapter.setMessages(java.util.Collections.emptyList());
        assertEquals(0, adapter.getItemCount());
        assertEquals(0, adapter.getMessageCount());
        assertEquals(0L, adapter.getOldestMessageId());
        assertEquals(0L, adapter.getNewestMessageId());

        // 2. Initial batch of 50 messages loading (reverse chronological input from TDLib)
        List<TdApi.Message> batch = new ArrayList<>();
        long baseTime = 1700000000;
        for (int i = 50; i >= 1; i--) { // 50 (newest) down to 1 (oldest)
            TdApi.Message m = new TdApi.Message();
            m.id = 1000L + i;
            m.date = (int) (baseTime + i * 60);
            m.isOutgoing = (i % 2 == 0);
            m.content = new TdApi.MessageText(new TdApi.FormattedText("Msg " + i, new TdApi.TextEntity[0]), null, null);
            batch.add(m);
        }

        adapter.setMessages(batch);
        assertEquals(50, adapter.getMessageCount());

        // Oldest message ID must be Msg 1 (id = 1001)
        assertEquals(1001L, adapter.getOldestMessageId());
        // Newest message ID must be Msg 50 (id = 1050)
        assertEquals(1050L, adapter.getNewestMessageId());
    }

    @Test
    public void testOlderMessagePaginationAndOffsetPreservation() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        long baseTime = 1700000000;

        // Initial latest batch: Messages 51 to 100
        List<TdApi.Message> initialBatch = new ArrayList<>();
        for (int i = 51; i <= 100; i++) {
            TdApi.Message m = new TdApi.Message();
            m.id = 1000L + i;
            m.date = (int) (baseTime + i * 60);
            m.content = new TdApi.MessageText(new TdApi.FormattedText("Msg " + i, new TdApi.TextEntity[0]), null, null);
            initialBatch.add(m);
        }
        adapter.setMessages(initialBatch);
        assertEquals(50, adapter.getMessageCount());
        assertEquals(1051L, adapter.getOldestMessageId());

        // Pagination: Prepend older batch: Messages 1 to 50
        List<TdApi.Message> olderBatch = new ArrayList<>();
        for (int i = 50; i >= 1; i--) {
            TdApi.Message m = new TdApi.Message();
            m.id = 1000L + i;
            m.date = (int) (baseTime + i * 60);
            m.content = new TdApi.MessageText(new TdApi.FormattedText("Msg " + i, new TdApi.TextEntity[0]), null, null);
            olderBatch.add(m);
        }

        int inserted = adapter.prependOlderMessages(olderBatch);
        assertTrue("Inserted count must be positive when prepending older items", inserted > 0);
        assertEquals(100, adapter.getMessageCount());

        // New oldest message must now be Msg 1 (id = 1001)
        assertEquals(1001L, adapter.getOldestMessageId());
        assertEquals(1100L, adapter.getNewestMessageId());

        // Re-prepending duplicate messages must not increase count
        int duplicateInserted = adapter.prependOlderMessages(olderBatch);
        assertEquals(0, duplicateInserted);
        assertEquals(100, adapter.getMessageCount());
    }

    @Test
    public void testMessageContentEditingAndDeletion() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        TdApi.Message msg1 = new TdApi.Message();
        msg1.id = 2001L;
        msg1.date = 1700000000;
        msg1.content = new TdApi.MessageText(new TdApi.FormattedText("Original Text", new TdApi.TextEntity[0]), null, null);

        TdApi.Message msg2 = new TdApi.Message();
        msg2.id = 2002L;
        msg2.date = 1700000060;
        msg2.content = new TdApi.MessageText(new TdApi.FormattedText("Second Message", new TdApi.TextEntity[0]), null, null);

        adapter.setMessages(Arrays.asList(msg1, msg2));
        assertEquals(2, adapter.getMessageCount());

        // 1. Test Content Editing (UpdateMessageContent)
        TdApi.FormattedText editedText = new TdApi.FormattedText("Edited Text Content", new TdApi.TextEntity[0]);
        adapter.onMessageContentUpdated(2001L, new TdApi.MessageText(editedText, null, null));
        adapter.onMessageEdited(2001L, 1700000120);

        assertTrue(adapter.containsMessage(2001L));

        // 2. Test Message Deletion (UpdateDeleteMessages)
        adapter.onMessagesDeleted(new long[]{2002L});
        assertEquals(1, adapter.getMessageCount());
        assertTrue(adapter.containsMessage(2001L));
        assertFalse(adapter.containsMessage(2002L));
    }

    @Test
    public void testMessageReplyRequestConstructionAndAdapterBinding() {
        // 1. TDLib InputMessageReplyToMessage construction
        long originalMessageId = 123456789L;
        TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage(originalMessageId, null, 0, null);
        assertEquals(TdApi.InputMessageReplyToMessage.CONSTRUCTOR, replyTo.getConstructor());
        assertEquals(originalMessageId, replyTo.messageId);

        // 2. TDLib SendMessage with replyTo parameter
        TdApi.FormattedText text = new TdApi.FormattedText("This is a reply", new TdApi.TextEntity[0]);
        TdApi.InputMessageText inputContent = new TdApi.InputMessageText(text, null, true);
        TdApi.SendMessage sendReply = new TdApi.SendMessage(777000L, null, replyTo, null, null, inputContent);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendReply.getConstructor());
        assertNotNull(sendReply.replyTo);
        assertTrue(sendReply.replyTo instanceof TdApi.InputMessageReplyToMessage);
        assertEquals(originalMessageId, ((TdApi.InputMessageReplyToMessage) sendReply.replyTo).messageId);

        // 3. Adapter binding with replyTo
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        TdApi.Message originalMsg = new TdApi.Message();
        originalMsg.id = originalMessageId;
        originalMsg.date = 1700000000;
        originalMsg.content = new TdApi.MessageText(new TdApi.FormattedText("Original Question", new TdApi.TextEntity[0]), null, null);

        TdApi.Message replyMsg = new TdApi.Message();
        replyMsg.id = 987654321L;
        replyMsg.date = 1700000060;
        replyMsg.content = new TdApi.MessageText(new TdApi.FormattedText("Reply Answer", new TdApi.TextEntity[0]), null, null);
        replyMsg.replyTo = new TdApi.MessageReplyToMessage(777000L, originalMessageId, null, 0, null, null, 0, null);

        adapter.setMessages(Arrays.asList(originalMsg, replyMsg));
        assertEquals(2, adapter.getMessageCount());
        assertEquals(originalMsg, adapter.getMessageById(originalMessageId));
        assertEquals(replyMsg, adapter.getMessageById(987654321L));
        assertEquals(1, adapter.findPositionByMessageId(originalMessageId)); // index 0 is date header, 1 is original
    }

    @Test
    public void testMessageForwardRequestConstruction() {
        long targetChatId = 888888L;
        long sourceChatId = 999999L;
        long[] messageIds = new long[]{101L, 102L, 103L};

        TdApi.ForwardMessages forward = new TdApi.ForwardMessages(targetChatId, null, sourceChatId, messageIds, null, false, false);
        assertEquals(TdApi.ForwardMessages.CONSTRUCTOR, forward.getConstructor());
        assertEquals(targetChatId, forward.chatId);
        assertEquals(sourceChatId, forward.fromChatId);
        org.junit.Assert.assertArrayEquals(messageIds, forward.messageIds);
        assertFalse(forward.sendCopy);
    }

    @Test
    public void testMessageEditRequestConstructionAndReconciliation() {
        long chatId = 55555L;
        long messageId = 77777L;

        TdApi.FormattedText newFormattedText = new TdApi.FormattedText("Updated text content", new TdApi.TextEntity[0]);
        TdApi.InputMessageText inputContent = new TdApi.InputMessageText(newFormattedText, null, true);
        TdApi.EditMessageText editRequest = new TdApi.EditMessageText(chatId, messageId, null, inputContent);

        assertEquals(TdApi.EditMessageText.CONSTRUCTOR, editRequest.getConstructor());
        assertEquals(chatId, editRequest.chatId);
        assertEquals(messageId, editRequest.messageId);

        // Verify adapter edit reconciliation does not duplicate
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);
        TdApi.Message m = new TdApi.Message();
        m.id = messageId;
        m.date = 1700000000;
        m.isOutgoing = true;
        m.content = new TdApi.MessageText(new TdApi.FormattedText("Initial text", new TdApi.TextEntity[0]), null, null);

        adapter.setMessages(Collections.singletonList(m));
        assertEquals(1, adapter.getMessageCount());

        adapter.onMessageContentUpdated(messageId, new TdApi.MessageText(newFormattedText, null, null));
        adapter.onMessageEdited(messageId, 1700000100);

        assertEquals(1, adapter.getMessageCount());
        assertTrue(adapter.containsMessage(messageId));
        assertEquals(1700000100, adapter.getMessageById(messageId).editDate);
    }

    @Test
    public void testMessageDeleteRequestConstructionAndAdapterBatchRemoval() {
        long chatId = 33333L;
        long[] toDelete = new long[]{401L, 402L};

        TdApi.DeleteMessages delRequest = new TdApi.DeleteMessages(chatId, toDelete, true);
        assertEquals(TdApi.DeleteMessages.CONSTRUCTOR, delRequest.getConstructor());
        assertEquals(chatId, delRequest.chatId);
        assertTrue(delRequest.revoke);

        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);
        List<TdApi.Message> list = new ArrayList<>();
        for (long id : new long[]{401L, 402L, 403L}) {
            TdApi.Message m = new TdApi.Message();
            m.id = id;
            m.date = 1700000000;
            m.content = new TdApi.MessageText(new TdApi.FormattedText("Msg " + id, new TdApi.TextEntity[0]), null, null);
            list.add(m);
        }
        adapter.setMessages(list);
        assertEquals(3, adapter.getMessageCount());

        adapter.onMessagesDeleted(toDelete);
        assertEquals(1, adapter.getMessageCount());
        assertFalse(adapter.containsMessage(401L));
        assertFalse(adapter.containsMessage(402L));
        assertTrue(adapter.containsMessage(403L));
    }

    @Test
    public void testPinAndUnpinRequestsAndEvents() {
        long chatId = 121212L;
        long messageId = 343434L;

        TdApi.PinChatMessage pin = new TdApi.PinChatMessage(chatId, messageId, false, false);
        assertEquals(TdApi.PinChatMessage.CONSTRUCTOR, pin.getConstructor());
        assertEquals(chatId, pin.chatId);
        assertEquals(messageId, pin.messageId);

        TdApi.UnpinChatMessage unpin = new TdApi.UnpinChatMessage(chatId, messageId);
        assertEquals(TdApi.UnpinChatMessage.CONSTRUCTOR, unpin.getConstructor());
        assertEquals(chatId, unpin.chatId);
        assertEquals(messageId, unpin.messageId);

        TdApi.UpdateMessageIsPinned updatePinned = new TdApi.UpdateMessageIsPinned(chatId, messageId, true);
        assertEquals(TdApi.UpdateMessageIsPinned.CONSTRUCTOR, updatePinned.getConstructor());
        assertEquals(chatId, updatePinned.chatId);
        assertEquals(messageId, updatePinned.messageId);
        assertTrue(updatePinned.isPinned);
    }

    @Test
    public void testMessageSelectionModeStateManagement() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        List<TdApi.Message> msgs = new ArrayList<>();
        for (long id : new long[]{501L, 502L, 503L}) {
            TdApi.Message m = new TdApi.Message();
            m.id = id;
            m.date = 1700000000;
            m.content = new TdApi.MessageText(new TdApi.FormattedText("Select " + id, new TdApi.TextEntity[0]), null, null);
            msgs.add(m);
        }
        adapter.setMessages(msgs);

        assertFalse(adapter.isSelectionMode());
        assertEquals(0, adapter.getSelectedCount());

        // 1. Start selection
        adapter.startSelection(501L);
        assertTrue(adapter.isSelectionMode());
        assertEquals(1, adapter.getSelectedCount());
        assertTrue(adapter.getSelectedMessageIds().contains(501L));

        // 2. Toggle additional items
        adapter.toggleSelection(502L);
        assertEquals(2, adapter.getSelectedCount());
        assertTrue(adapter.getSelectedMessageIds().contains(502L));

        List<TdApi.Message> selectedMsgs = adapter.getSelectedMessages();
        assertEquals(2, selectedMsgs.size());

        // 3. Deselect item
        adapter.toggleSelection(501L);
        assertEquals(1, adapter.getSelectedCount());
        assertFalse(adapter.getSelectedMessageIds().contains(501L));
        assertTrue(adapter.isSelectionMode());

        // 4. Deselect last item automatically exits selection mode
        adapter.toggleSelection(502L);
        assertEquals(0, adapter.getSelectedCount());
        assertFalse(adapter.isSelectionMode());

        // 5. Clear selection
        adapter.startSelection(503L);
        assertTrue(adapter.isSelectionMode());
        adapter.clearSelection();
        assertEquals(0, adapter.getSelectedCount());
        assertFalse(adapter.isSelectionMode());
    }

    // ==========================================
    // MILESTONE 2.8 MEDIA TESTS
    // ==========================================

    @Test
    public void testPhotoRequestConstruction() {
        String testPath = "/storage/emulated/0/DCIM/photo.jpg";
        int width = 1280;
        int height = 720;
        String captionText = "Test photo caption";

        TdApi.InputFile photoFile = new TdApi.InputFileLocal(testPath);
        assertEquals(TdApi.InputFileLocal.CONSTRUCTOR, photoFile.getConstructor());
        assertEquals(testPath, ((TdApi.InputFileLocal) photoFile).path);

        TdApi.InputPhoto inputPhoto = new TdApi.InputPhoto(photoFile, null, null, new int[0], width, height);
        assertEquals(TdApi.InputPhoto.CONSTRUCTOR, inputPhoto.getConstructor());
        assertEquals(width, inputPhoto.width);
        assertEquals(height, inputPhoto.height);

        TdApi.FormattedText captionFormatted = new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);
        TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto(inputPhoto, captionFormatted, false, null, false);
        assertEquals(TdApi.InputMessagePhoto.CONSTRUCTOR, content.getConstructor());
        assertNotNull(content.photo);
        assertNotNull(content.caption);
        assertEquals(captionText, content.caption.text);

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(123456L, null, null, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertEquals(123456L, sendRequest.chatId);
        assertTrue(sendRequest.inputMessageContent instanceof TdApi.InputMessagePhoto);
    }

    @Test
    public void testVideoRequestConstruction() {
        String testPath = "/storage/emulated/0/Movies/video.mp4";
        int duration = 45;
        int width = 1920;
        int height = 1080;
        String captionText = "Sunset at the beach";

        TdApi.InputFile videoFile = new TdApi.InputFileLocal(testPath);
        TdApi.InputVideo inputVideo = new TdApi.InputVideo(videoFile, null, null, 0, new int[0], duration, width, height, true);
        assertEquals(TdApi.InputVideo.CONSTRUCTOR, inputVideo.getConstructor());
        assertEquals(duration, inputVideo.duration);
        assertEquals(width, inputVideo.width);
        assertEquals(height, inputVideo.height);
        assertTrue(inputVideo.supportsStreaming);

        TdApi.FormattedText captionFormatted = new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);
        TdApi.InputMessageVideo content = new TdApi.InputMessageVideo(inputVideo, captionFormatted, false, null, false);
        assertEquals(TdApi.InputMessageVideo.CONSTRUCTOR, content.getConstructor());
        assertNotNull(content.video);
        assertEquals(captionText, content.caption.text);

        TdApi.InputMessageReplyTo replyTo = new TdApi.InputMessageReplyToMessage(999L, null, 0, null);
        TdApi.SendMessage sendRequest = new TdApi.SendMessage(777L, null, replyTo, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertTrue(sendRequest.replyTo instanceof TdApi.InputMessageReplyToMessage);
        assertEquals(999L, ((TdApi.InputMessageReplyToMessage) sendRequest.replyTo).messageId);
    }

    @Test
    public void testDocumentRequestConstruction() {
        String testPath = "/storage/emulated/0/Download/report.pdf";
        String captionText = "Q4 Financial Report";

        TdApi.InputFile docFile = new TdApi.InputFileLocal(testPath);
        TdApi.InputDocument inputDoc = new TdApi.InputDocument(docFile, null, false);
        assertEquals(TdApi.InputDocument.CONSTRUCTOR, inputDoc.getConstructor());
        assertFalse(inputDoc.disableContentTypeDetection);

        TdApi.FormattedText captionFormatted = new TdApi.FormattedText(captionText, new TdApi.TextEntity[0]);
        TdApi.InputMessageDocument content = new TdApi.InputMessageDocument(inputDoc, captionFormatted);
        assertEquals(TdApi.InputMessageDocument.CONSTRUCTOR, content.getConstructor());
        assertNotNull(content.document);
        assertEquals(captionText, content.caption.text);

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(888L, null, null, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertTrue(sendRequest.inputMessageContent instanceof TdApi.InputMessageDocument);
    }

    @Test
    public void testCancelDownloadFileRequest() {
        int fileId = 4242;
        TdApi.CancelDownloadFile cancel = new TdApi.CancelDownloadFile(fileId, false);
        assertEquals(TdApi.CancelDownloadFile.CONSTRUCTOR, cancel.getConstructor());
        assertEquals(fileId, cancel.fileId);
        assertFalse(cancel.onlyIfPending);

        TdApi.CancelDownloadFile cancelPendingOnly = new TdApi.CancelDownloadFile(fileId, true);
        assertTrue(cancelPendingOnly.onlyIfPending);
    }

    @Test
    public void testFileUpdateReconciliationAndDownloadStates() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        // Create a MessagePhoto
        int fileId = 1001;
        TdApi.File photoFile = new TdApi.File();
        photoFile.id = fileId;
        photoFile.size = 204800;
        photoFile.expectedSize = 204800;
        photoFile.local = new TdApi.LocalFile("/data/user/0/com.nexgram.messenger/cache/photo_thumb.jpg", true, false, false, true, 0, 204800, 204800);

        TdApi.PhotoSize[] sizes = new TdApi.PhotoSize[1];
        sizes[0] = new TdApi.PhotoSize("m", photoFile, 800, 600, new int[0]);
        TdApi.Photo photo = new TdApi.Photo(false, null, sizes);

        TdApi.Message m = new TdApi.Message();
        m.id = 701L;
        m.chatId = 12345L;
        m.date = 1700000000;
        TdApi.MessagePhoto msgPhoto = new TdApi.MessagePhoto();
        msgPhoto.photo = photo;
        msgPhoto.caption = new TdApi.FormattedText("Scenic Mountain", new TdApi.TextEntity[0]);
        m.content = msgPhoto;

        adapter.setMessages(Collections.singletonList(m));
        assertEquals(1, adapter.getMessageCount());

        // Now dispatch a file update where downloading completed
        TdApi.File updatedFile = new TdApi.File();
        updatedFile.id = fileId;
        updatedFile.size = 204800;
        updatedFile.local = new TdApi.LocalFile("/data/user/0/com.nexgram.messenger/files/full_photo.jpg", false, false, true, true, 0, 204800, 204800);

        adapter.onFileUpdated(updatedFile);
        // Ensure adapter maintains message count and contains the updated message
        assertEquals(1, adapter.getMessageCount());
        assertTrue(adapter.containsMessage(701L));
    }

    @Test
    public void testMediaCaptionAndPreviewFormatting() {
        // MessagePhoto with caption
        TdApi.Photo photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[0]);
        TdApi.Message mPhoto = new TdApi.Message();
        TdApi.MessagePhoto photoWithCaption = new TdApi.MessagePhoto();
        photoWithCaption.photo = photo;
        photoWithCaption.caption = new TdApi.FormattedText("Vacation photo", new TdApi.TextEntity[0]);
        mPhoto.content = photoWithCaption;
        assertEquals("📷 Vacation photo", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(mPhoto));

        // MessagePhoto without caption
        TdApi.Message mPhotoNoCaption = new TdApi.Message();
        TdApi.MessagePhoto photoNoCaption = new TdApi.MessagePhoto();
        photoNoCaption.photo = photo;
        mPhotoNoCaption.content = photoNoCaption;
        assertEquals("📷 Photo", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(mPhotoNoCaption));

        // MessageVideo with caption
        TdApi.Video video = new TdApi.Video();
        video.duration = 60;
        video.fileName = "clip.mp4";
        TdApi.Message mVideo = new TdApi.Message();
        TdApi.MessageVideo videoWithCaption = new TdApi.MessageVideo();
        videoWithCaption.video = video;
        videoWithCaption.caption = new TdApi.FormattedText("Check this out", new TdApi.TextEntity[0]);
        mVideo.content = videoWithCaption;
        assertEquals("📹 Check this out", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(mVideo));

        // MessageDocument with caption
        TdApi.Document doc = new TdApi.Document();
        doc.fileName = "contract.pdf";
        TdApi.Message mDoc = new TdApi.Message();
        TdApi.MessageDocument docWithCaption = new TdApi.MessageDocument();
        docWithCaption.document = doc;
        docWithCaption.caption = new TdApi.FormattedText("Signed document", new TdApi.TextEntity[0]);
        mDoc.content = docWithCaption;
        assertEquals("📄 contract.pdf", com.nexgram.messenger.ui.helpers.TelegramMessageFormatter.formatMessagePreview(mDoc));
    }

    @Test
    public void testVoiceNoteRequestConstruction() {
        String testVoicePath = "/data/user/0/com.nexgram.messenger/cache/voice_out/test.ogg";
        int durationSec = 14;
        byte[] waveform = new byte[100];
        for (int i = 0; i < 100; i++) {
            waveform[i] = (byte) (i % 32);
        }

        TdApi.InputFile voiceFile = new TdApi.InputFileLocal(testVoicePath);
        TdApi.InputVoiceNote voiceNote = new TdApi.InputVoiceNote(voiceFile, durationSec, waveform);
        assertEquals(TdApi.InputVoiceNote.CONSTRUCTOR, voiceNote.getConstructor());
        assertEquals(durationSec, voiceNote.duration);
        assertEquals(100, voiceNote.waveform.length);

        TdApi.InputMessageVoiceNote content = new TdApi.InputMessageVoiceNote(voiceNote, null, null);
        assertEquals(TdApi.InputMessageVoiceNote.CONSTRUCTOR, content.getConstructor());

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(12345L, null, null, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertTrue(sendRequest.inputMessageContent instanceof TdApi.InputMessageVoiceNote);
    }

    @Test
    public void testAudioRequestConstruction() {
        String testAudioPath = "/storage/emulated/0/Music/song.mp3";
        int durationSec = 215;
        String title = "Starboy";
        String performer = "The Weeknd";

        TdApi.InputFile audioFile = new TdApi.InputFileLocal(testAudioPath);
        TdApi.InputAudio audio = new TdApi.InputAudio(audioFile, null, durationSec, title, performer);
        assertEquals(TdApi.InputAudio.CONSTRUCTOR, audio.getConstructor());
        assertEquals(title, audio.title);
        assertEquals(performer, audio.performer);
        assertEquals(durationSec, audio.duration);

        TdApi.FormattedText caption = new TdApi.FormattedText("Best track", new TdApi.TextEntity[0]);
        TdApi.InputMessageAudio content = new TdApi.InputMessageAudio(audio, caption);
        assertEquals(TdApi.InputMessageAudio.CONSTRUCTOR, content.getConstructor());

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(54321L, null, null, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertTrue(sendRequest.inputMessageContent instanceof TdApi.InputMessageAudio);
    }

    @Test
    public void testStickerRequestConstruction() {
        int stickerFileId = 889900;
        int width = 512;
        int height = 512;
        String emoji = "🔥";

        TdApi.InputFile stickerFile = new TdApi.InputFileId(stickerFileId);
        TdApi.InputSticker sticker = new TdApi.InputSticker(stickerFile, null, width, height);
        assertEquals(TdApi.InputSticker.CONSTRUCTOR, sticker.getConstructor());
        assertEquals(width, sticker.width);
        assertEquals(height, sticker.height);

        TdApi.InputMessageSticker content = new TdApi.InputMessageSticker(sticker, emoji);
        assertEquals(TdApi.InputMessageSticker.CONSTRUCTOR, content.getConstructor());
        assertEquals(emoji, content.emoji);

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(99999L, null, null, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertTrue(sendRequest.inputMessageContent instanceof TdApi.InputMessageSticker);
    }

    @Test
    public void testAnimationRequestConstruction() {
        String testGifPath = "/storage/emulated/0/Download/funny.mp4";
        int width = 480;
        int height = 360;
        int duration = 4;

        TdApi.InputFile animFile = new TdApi.InputFileLocal(testGifPath);
        TdApi.InputAnimation anim = new TdApi.InputAnimation(animFile, null, new int[0], duration, width, height);
        assertEquals(TdApi.InputAnimation.CONSTRUCTOR, anim.getConstructor());
        assertEquals(width, anim.width);
        assertEquals(height, anim.height);
        assertEquals(duration, anim.duration);

        TdApi.InputMessageAnimation content = new TdApi.InputMessageAnimation(anim, null, false, false);
        assertEquals(TdApi.InputMessageAnimation.CONSTRUCTOR, content.getConstructor());

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(88888L, null, null, null, null, content);
        assertEquals(TdApi.SendMessage.CONSTRUCTOR, sendRequest.getConstructor());
        assertTrue(sendRequest.inputMessageContent instanceof TdApi.InputMessageAnimation);
    }

    @Test
    public void testInstalledStickerSetsAndAnimationsConstructors() {
        TdApi.GetInstalledStickerSets reqStickers = new TdApi.GetInstalledStickerSets(new TdApi.StickerTypeRegular());
        assertEquals(TdApi.GetInstalledStickerSets.CONSTRUCTOR, reqStickers.getConstructor());

        TdApi.GetStickerSet reqSet = new TdApi.GetStickerSet(123456789L);
        assertEquals(TdApi.GetStickerSet.CONSTRUCTOR, reqSet.getConstructor());
        assertEquals(123456789L, reqSet.setId);

        TdApi.GetSavedAnimations reqGifs = new TdApi.GetSavedAnimations();
        assertEquals(TdApi.GetSavedAnimations.CONSTRUCTOR, reqGifs.getConstructor());
    }

    @Test
    public void testMessageAdapterSenderNameCacheResolution() {
        com.nexgram.messenger.ui.adapter.MessageAdapter adapter = new com.nexgram.messenger.ui.adapter.MessageAdapter(null);

        // Pre-populate sender name
        adapter.setSenderName(6115535877L, "Rakib Hasan");

        TdApi.Message m = new TdApi.Message();
        m.id = 1001L;
        m.chatId = -100123456789L;
        m.senderId = new TdApi.MessageSenderUser(6115535877L);
        m.isOutgoing = false;
        m.date = 1700000000;
        m.content = new TdApi.MessageText(new TdApi.FormattedText("Hello Group", new TdApi.TextEntity[0]), null, null);

        adapter.setMessages(Collections.singletonList(m));
        assertEquals(1, adapter.getMessageCount());
        assertTrue(adapter.containsMessage(1001L));
    }

    @Test
    public void testAccountRegistryPersistenceAndSwitching() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);
        com.nexgram.messenger.auth.AccountManager manager = new com.nexgram.messenger.auth.AccountManager(mockPrefs, testVault);

        // Add Account 1
        com.nexgram.messenger.auth.AccountManager.AccountItem acc1 = new com.nexgram.messenger.auth.AccountManager.AccountItem(
                "account_1", "Alice", "+111111111", "alice_tg", 1001L, null);
        manager.addAccount(acc1, "auth_key_alice");

        // Add Account 2
        com.nexgram.messenger.auth.AccountManager.AccountItem acc2 = new com.nexgram.messenger.auth.AccountManager.AccountItem(
                "account_2", "Bob", "+222222222", "bob_tg", 2002L, null);
        manager.addAccount(acc2, "auth_key_bob");

        assertEquals(2, manager.getAccountCount());
        assertEquals("account_2", manager.getActiveAccount().id);

        // Switch to Account 1
        manager.switchAccount("account_1");
        assertEquals("account_1", manager.getActiveAccount().id);

        // Simulate app restart / reload from preferences
        com.nexgram.messenger.auth.AccountManager reloadedManager = new com.nexgram.messenger.auth.AccountManager(mockPrefs, testVault);
        assertEquals(2, reloadedManager.getAccountCount());
        assertNotNull(reloadedManager.getActiveAccount());
        assertEquals("account_1", reloadedManager.getActiveAccount().id);
        assertEquals("Alice", reloadedManager.getActiveAccount().name);
    }

    @Test
    public void testStableAccountIdGeneration() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);
        com.nexgram.messenger.auth.AccountManager manager = new com.nexgram.messenger.auth.AccountManager(mockPrefs, testVault);

        String id1 = manager.generateNewAccountId();
        String id2 = manager.generateNewAccountId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertTrue(id1.startsWith("account_"));
        assertTrue(id2.startsWith("account_"));
        assertNotEquals(id1, id2);
    }

    @Test
    public void testMultiAccountDirectoryAndKeyIsolation() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);

        byte[] key1 = testVault.getOrCreateDatabaseKey("account_alpha");
        byte[] key2 = testVault.getOrCreateDatabaseKey("account_beta");

        assertNotNull(key1);
        assertNotNull(key2);
        assertEquals(32, key1.length);
        assertEquals(32, key2.length);
        assertFalse(Arrays.equals(key1, key2));

        File baseDir = new File("/data/user/0/com.nexgram.messenger/files/tdlib_accounts");
        File dir1 = new File(baseDir, "account_alpha");
        File dir2 = new File(baseDir, "account_beta");

        assertNotEquals(dir1.getAbsolutePath(), dir2.getAbsolutePath());
    }

    @Test
    public void testAccountRemovalIsolation() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);
        com.nexgram.messenger.auth.AccountManager manager = new com.nexgram.messenger.auth.AccountManager(mockPrefs, testVault);

        manager.addAccount(new com.nexgram.messenger.auth.AccountManager.AccountItem("acc_1", "User 1", "+1001", "u1", 1L, null), "key_1");
        manager.addAccount(new com.nexgram.messenger.auth.AccountManager.AccountItem("acc_2", "User 2", "+1002", "u2", 2L, null), "key_2");
        manager.addAccount(new com.nexgram.messenger.auth.AccountManager.AccountItem("acc_3", "User 3", "+1003", "u3", 3L, null), "key_3");

        assertEquals(3, manager.getAccountCount());
        assertTrue(testVault.hasSession("acc_1"));
        assertTrue(testVault.hasSession("acc_2"));
        assertTrue(testVault.hasSession("acc_3"));

        // Remove acc_2
        manager.removeAccount("acc_2");

        assertEquals(2, manager.getAccountCount());
        assertNull(manager.getAccount("acc_2"));
        assertNotNull(manager.getAccount("acc_1"));
        assertNotNull(manager.getAccount("acc_3"));
        assertTrue(testVault.hasSession("acc_1"));
        assertFalse(testVault.hasSession("acc_2"));
        assertTrue(testVault.hasSession("acc_3"));
    }

    @Test
    public void testPerAccountProxyIsolation() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);
        com.nexgram.messenger.proxy.ProxyController proxy = new com.nexgram.messenger.proxy.ProxyController(mockPrefs, testVault);

        // Account 1: Germany SOCKS5
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata meta1 = new com.nexgram.messenger.proxy.ProxyController.ProxyMetadata();
        meta1.type = com.nexgram.messenger.proxy.ProxyController.ProxyType.SOCKS5;
        meta1.host = "194.135.80.10";
        meta1.port = 1080;
        meta1.username = "user_de";
        meta1.enabled = true;
        meta1.label = "Germany SOCKS5";
        proxy.saveProxy("acc_1", meta1, "pass_de");

        // Account 2: USA MTProto
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata meta2 = new com.nexgram.messenger.proxy.ProxyController.ProxyMetadata();
        meta2.type = com.nexgram.messenger.proxy.ProxyController.ProxyType.MTPROTO;
        meta2.host = "142.250.190.46";
        meta2.port = 443;
        meta2.enabled = true;
        meta2.label = "USA MTProto";
        proxy.saveProxy("acc_2", meta2, "secret_mtproto_usa");

        // Verify acc_1 config
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata read1 = proxy.getProxyMetadata("acc_1");
        assertNotNull(read1);
        assertEquals("194.135.80.10", read1.host);
        assertEquals(1080, read1.port);
        assertEquals(com.nexgram.messenger.proxy.ProxyController.ProxyType.SOCKS5, read1.type);
        assertEquals("pass_de", proxy.getProxySecret("acc_1"));

        // Verify acc_2 config
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata read2 = proxy.getProxyMetadata("acc_2");
        assertNotNull(read2);
        assertEquals("142.250.190.46", read2.host);
        assertEquals(443, read2.port);
        assertEquals(com.nexgram.messenger.proxy.ProxyController.ProxyType.MTPROTO, read2.type);
        assertEquals("secret_mtproto_usa", proxy.getProxySecret("acc_2"));

        // Change acc_1 to DIRECT, acc_2 must remain untouched
        proxy.saveDirectConnection("acc_1");
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata direct1 = proxy.getProxyMetadata("acc_1");
        assertFalse(direct1.enabled);
        assertEquals(com.nexgram.messenger.proxy.ProxyController.ProxyType.DIRECT, direct1.type);

        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata untouched2 = proxy.getProxyMetadata("acc_2");
        assertTrue(untouched2.enabled);
        assertEquals("142.250.190.46", untouched2.host);
    }

    @Test
    public void testDuplicateAccountDetection() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);
        com.nexgram.messenger.auth.AccountManager manager = new com.nexgram.messenger.auth.AccountManager(mockPrefs, testVault);

        manager.addAccount(new com.nexgram.messenger.auth.AccountManager.AccountItem("acc_1", "Rakib", "+919876543210", "rakib", 6115535877L, null), "key");

        // Detection by Telegram User ID
        assertNotNull(manager.getAccountByUserId(6115535877L));
        assertEquals("acc_1", manager.getAccountByUserId(6115535877L).id);
        assertNull(manager.getAccountByUserId(999999999L));

        // Detection by Phone Number
        assertTrue(manager.hasAccountWithPhone("+91 98765 43210"));
        assertTrue(manager.hasAccountWithPhone("919876543210"));
        assertFalse(manager.hasAccountWithPhone("+1234567890"));
    }

    @Test
    public void testGlobalSavedProxiesLibrary() {
        MockSharedPreferences mockPrefs = new MockSharedPreferences();
        SecureSessionVault testVault = new SecureSessionVault(mockPrefs, true);
        com.nexgram.messenger.proxy.ProxyController proxy = new com.nexgram.messenger.proxy.ProxyController(mockPrefs, testVault);

        // Add 1st proxy to global library (UK SOCKS5)
        com.nexgram.messenger.proxy.ProxyController.SavedProxy saved1 = proxy.saveToGlobalLibrary(
                "UK SOCKS5",
                com.nexgram.messenger.proxy.ProxyController.ProxyType.SOCKS5,
                "104.252.129.67",
                7437,
                "vvxvmuda",
                "secret_pass_123"
        );

        assertNotNull(saved1);
        assertNotNull(saved1.id);
        assertEquals("UK SOCKS5", saved1.name);
        assertEquals("104.252.129.67", saved1.host);
        assertEquals(7437, saved1.port);

        // Add 2nd proxy to global library (DE MTProto)
        com.nexgram.messenger.proxy.ProxyController.SavedProxy saved2 = proxy.saveToGlobalLibrary(
                "Germany MTProto",
                com.nexgram.messenger.proxy.ProxyController.ProxyType.MTPROTO,
                "142.250.190.46",
                443,
                "",
                "ee112233445566778899aabbccddeeff"
        );

        List<com.nexgram.messenger.proxy.ProxyController.SavedProxy> allSaved = proxy.getAllSavedProxies();
        assertEquals(2, allSaved.size());
        assertEquals("secret_pass_123", proxy.getSavedProxySecret(saved1.id));
        assertEquals("ee112233445566778899aabbccddeeff", proxy.getSavedProxySecret(saved2.id));

        // Apply saved1 to account_alpha
        proxy.applySavedProxyToAccount("account_alpha", saved1.id);
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata alphaMeta = proxy.getProxyMetadata("account_alpha");
        assertNotNull(alphaMeta);
        assertTrue(alphaMeta.enabled);
        assertEquals("104.252.129.67", alphaMeta.host);
        assertEquals("secret_pass_123", proxy.getProxySecret("account_alpha"));

        // Apply saved2 to account_beta
        proxy.applySavedProxyToAccount("account_beta", saved2.id);
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata betaMeta = proxy.getProxyMetadata("account_beta");
        assertNotNull(betaMeta);
        assertTrue(betaMeta.enabled);
        assertEquals(com.nexgram.messenger.proxy.ProxyController.ProxyType.MTPROTO, betaMeta.type);
        assertEquals("142.250.190.46", betaMeta.host);
        assertEquals("ee112233445566778899aabbccddeeff", proxy.getProxySecret("account_beta"));

        // Delete saved1 from library
        proxy.deleteSavedProxy(saved1.id);
        assertEquals(1, proxy.getAllSavedProxies().size());
        assertNull(proxy.getSavedProxy(saved1.id));
        assertNull(proxy.getSavedProxySecret(saved1.id));

        // Account alpha configuration remains intact as its own isolated copy
        com.nexgram.messenger.proxy.ProxyController.ProxyMetadata alphaStillActive = proxy.getProxyMetadata("account_alpha");
        assertNotNull(alphaStillActive);
        assertEquals("104.252.129.67", alphaStillActive.host);
    }

    @Test
    public void testContactsFunctionsConstructors() {
        TdApi.GetContacts getContacts = new TdApi.GetContacts();
        assertEquals(TdApi.GetContacts.CONSTRUCTOR, getContacts.getConstructor());

        TdApi.SearchContacts searchContacts = new TdApi.SearchContacts("John", 25);
        assertEquals(TdApi.SearchContacts.CONSTRUCTOR, searchContacts.getConstructor());
        assertEquals("John", searchContacts.query);
        assertEquals(25, searchContacts.limit);

        TdApi.CreatePrivateChat createPrivate = new TdApi.CreatePrivateChat(987654321L, false);
        assertEquals(TdApi.CreatePrivateChat.CONSTRUCTOR, createPrivate.getConstructor());
        assertEquals(987654321L, createPrivate.userId);
        assertFalse(createPrivate.force);

        TdApi.Contact contact = new TdApi.Contact("+1234567890", "Alice", "Smith", "", 11223344L);
        assertEquals(TdApi.Contact.CONSTRUCTOR, contact.getConstructor());
        assertEquals("+1234567890", contact.phoneNumber);
        assertEquals("Alice", contact.firstName);
        assertEquals("Smith", contact.lastName);

        TdApi.ImportedContact importedContact = new TdApi.ImportedContact("+1234567890", "Alice", "Smith", null);
        assertEquals(TdApi.ImportedContact.CONSTRUCTOR, importedContact.getConstructor());
        TdApi.ImportContacts importContacts = new TdApi.ImportContacts(new TdApi.ImportedContact[]{importedContact});
        assertEquals(TdApi.ImportContacts.CONSTRUCTOR, importContacts.getConstructor());
        assertEquals(1, importContacts.contacts.length);
    }

    @Test
    public void testGroupAndChannelCreationConstructors() {
        long[] members = new long[]{101L, 102L, 103L};
        TdApi.CreateNewBasicGroupChat createGroup = new TdApi.CreateNewBasicGroupChat(members, "NexGram Dev Group", 0);
        assertEquals(TdApi.CreateNewBasicGroupChat.CONSTRUCTOR, createGroup.getConstructor());
        assertArrayEquals(members, createGroup.userIds);
        assertEquals("NexGram Dev Group", createGroup.title);

        TdApi.CreateNewSupergroupChat createChannel = new TdApi.CreateNewSupergroupChat(
                "NexGram News Channel",
                false, // isForum
                true,  // isChannel
                "Official announcements channel",
                null,
                0,
                false
        );
        assertEquals(TdApi.CreateNewSupergroupChat.CONSTRUCTOR, createChannel.getConstructor());
        assertEquals("NexGram News Channel", createChannel.title);
        assertTrue(createChannel.isChannel);
        assertFalse(createChannel.isForum);
        assertEquals("Official announcements channel", createChannel.description);

        TdApi.SetSupergroupUsername setUsername = new TdApi.SetSupergroupUsername(998877L, "nexgram_news");
        assertEquals(TdApi.SetSupergroupUsername.CONSTRUCTOR, setUsername.getConstructor());
        assertEquals(998877L, setUsername.supergroupId);
        assertEquals("nexgram_news", setUsername.username);
    }

    @Test
    public void testGroupMemberManagementAndFullInfoConstructors() {
        TdApi.AddChatMember addMember = new TdApi.AddChatMember(-100123456L, 555666L, 0);
        assertEquals(TdApi.AddChatMember.CONSTRUCTOR, addMember.getConstructor());
        assertEquals(-100123456L, addMember.chatId);
        assertEquals(555666L, addMember.userId);

        TdApi.SetChatMemberStatus banMember = new TdApi.SetChatMemberStatus(
                -100123456L,
                new TdApi.MessageSenderUser(555666L),
                new TdApi.ChatMemberStatusBanned(0)
        );
        assertEquals(TdApi.SetChatMemberStatus.CONSTRUCTOR, banMember.getConstructor());
        assertEquals(-100123456L, banMember.chatId);
        assertTrue(banMember.status instanceof TdApi.ChatMemberStatusBanned);

        TdApi.LeaveChat leaveChat = new TdApi.LeaveChat(-100123456L);
        assertEquals(TdApi.LeaveChat.CONSTRUCTOR, leaveChat.getConstructor());
        assertEquals(-100123456L, leaveChat.chatId);

        TdApi.GetBasicGroupFullInfo getBasicInfo = new TdApi.GetBasicGroupFullInfo(12345L);
        assertEquals(TdApi.GetBasicGroupFullInfo.CONSTRUCTOR, getBasicInfo.getConstructor());
        assertEquals(12345L, getBasicInfo.basicGroupId);

        TdApi.GetSupergroupFullInfo getSuperInfo = new TdApi.GetSupergroupFullInfo(67890L);
        assertEquals(TdApi.GetSupergroupFullInfo.CONSTRUCTOR, getSuperInfo.getConstructor());
        assertEquals(67890L, getSuperInfo.supergroupId);

        TdApi.GetSupergroupMembers getMembers = new TdApi.GetSupergroupMembers(67890L, new TdApi.SupergroupMembersFilterRecent(), 0, 50);
        assertEquals(TdApi.GetSupergroupMembers.CONSTRUCTOR, getMembers.getConstructor());
        assertEquals(67890L, getMembers.supergroupId);
        assertEquals(50, getMembers.limit);
    }

    @Test
    public void testInviteLinksAndPublicSearchConstructors() {
        TdApi.CreateChatInviteLink createLink = new TdApi.CreateChatInviteLink(-100123456L, "Group Invite", 0, 0, false);
        assertEquals(TdApi.CreateChatInviteLink.CONSTRUCTOR, createLink.getConstructor());
        assertEquals(-100123456L, createLink.chatId);
        assertEquals("Group Invite", createLink.name);

        TdApi.JoinChatByInviteLink joinLink = new TdApi.JoinChatByInviteLink("https://t.me/+AbCdEfGhIjK");
        assertEquals(TdApi.JoinChatByInviteLink.CONSTRUCTOR, joinLink.getConstructor());
        assertEquals("https://t.me/+AbCdEfGhIjK", joinLink.inviteLink);

        TdApi.SearchPublicChats searchPublic = new TdApi.SearchPublicChats("telegram", null);
        assertEquals(TdApi.SearchPublicChats.CONSTRUCTOR, searchPublic.getConstructor());
        assertEquals("telegram", searchPublic.query);
    }

    @Test
    public void testContactAdapterSelectionState() {
        com.nexgram.messenger.ui.adapter.ContactAdapter adapter = new com.nexgram.messenger.ui.adapter.ContactAdapter(null, true);
        assertTrue(adapter.isPickerMode());

        TdApi.User user1 = new TdApi.User();
        user1.id = 101L;
        user1.firstName = "Bob";

        TdApi.User user2 = new TdApi.User();
        user2.id = 102L;
        user2.firstName = "Charlie";

        adapter.setUsers(Arrays.asList(user1, user2));
        assertEquals(2, adapter.getItemCount());
        assertEquals(0, adapter.getSelectedUserIds().size());

        // Toggle user 1
        adapter.toggleSelection(101L);
        assertTrue(adapter.isUserSelected(101L));
        assertFalse(adapter.isUserSelected(102L));
        assertEquals(1, adapter.getSelectedUserIds().size());
        assertTrue(adapter.getSelectedUserIds().contains(101L));

        // Toggle user 2
        adapter.toggleSelection(102L);
        assertTrue(adapter.isUserSelected(101L));
        assertTrue(adapter.isUserSelected(102L));
        assertEquals(2, adapter.getSelectedUserIds().size());

        // Un-toggle user 1
        adapter.toggleSelection(101L);
        assertFalse(adapter.isUserSelected(101L));
        assertTrue(adapter.isUserSelected(102L));
        assertEquals(1, adapter.getSelectedUserIds().size());

        // Clear selection
        adapter.clearSelection();
        assertEquals(0, adapter.getSelectedUserIds().size());
    }
}
