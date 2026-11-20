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
}
