package com.nexgram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.auth.SecureSessionVault;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit Test Suite for NexGram Native AccountManager
 * Tests empty account state, adding multiple accounts, account switching,
 * and requiring secure vault presence.
 */
public class AccountManagerTest {

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

    private AccountManager manager;
    private SecureSessionVault vault;
    private MockSharedPreferences mockAccountPrefs;
    private MockSharedPreferences mockVaultPrefs;

    @Before
    public void setUp() {
        mockAccountPrefs = new MockSharedPreferences();
        mockVaultPrefs = new MockSharedPreferences();
        vault = new SecureSessionVault(mockVaultPrefs, false);
        manager = new AccountManager(mockAccountPrefs, vault);
    }

    @Test
    public void testInitialEmptyAccountState() {
        assertEquals(0, manager.getAccountCount());
        assertNull(manager.getActiveAccount());
        assertTrue(manager.getAllAccounts().isEmpty());
    }

    @Test
    public void testAddAndSwitchAccount() {
        AccountManager.AccountItem acc1 = new AccountManager.AccountItem("1001", "Alice", "+1001", "alice");
        AccountManager.AccountItem acc2 = new AccountManager.AccountItem("1002", "Bob", "+1002", "bob");

        manager.addAccount(acc1, "auth_key_alice");
        manager.addAccount(acc2, "auth_key_bob");

        assertEquals(2, manager.getAccountCount());
        assertEquals("1002", manager.getActiveAccount().id);

        manager.switchAccount("1001");
        assertEquals("1001", manager.getActiveAccount().id);
        assertEquals("Alice", manager.getActiveAccount().name);

        assertTrue(vault.hasSession("1001"));
        assertTrue(vault.hasSession("1002"));
    }

    @Test
    public void testRemoveAccountWipesSessionFromVault() {
        AccountManager.AccountItem acc = new AccountManager.AccountItem("2001", "Charlie", "+2001", "charlie");
        manager.addAccount(acc, "auth_key_charlie");

        assertTrue(vault.hasSession("2001"));
        manager.removeAccount("2001");

        assertEquals(0, manager.getAccountCount());
        assertNull(manager.getActiveAccount());
        assertFalse(vault.hasSession("2001"));
    }

    @Test(expected = SecurityException.class)
    public void testPlaintextStorageBlockedWhenVaultIsNull() {
        AccountManager nullVaultManager = new AccountManager(mockAccountPrefs, null);
        AccountManager.AccountItem item = new AccountManager.AccountItem("999", "Eve", "+999", "eve");
        nullVaultManager.addAccount(item, "secret_key");
    }
}
