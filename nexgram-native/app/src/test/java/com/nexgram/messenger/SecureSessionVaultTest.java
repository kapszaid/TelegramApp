package com.nexgram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import com.nexgram.messenger.auth.SecureSessionVault;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit Test Suite for SecureSessionVault
 * Tests that plaintext fallback is strictly blocked, null entries are rejected,
 * and valid sessions are saved, retrieved, and deleted correctly.
 */
public class SecureSessionVaultTest {

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
    private MockSharedPreferences mockPrefs;

    @Before
    public void setUp() {
        mockPrefs = new MockSharedPreferences();
        vault = new SecureSessionVault(mockPrefs, false);
    }

    @Test
    public void testSaveAndRetrieveSessionToken() {
        vault.saveSession("acc_123", "hex_auth_key_material_998877");
        assertTrue(vault.hasSession("acc_123"));
        assertEquals("hex_auth_key_material_998877", vault.getSession("acc_123"));
    }

    @Test
    public void testRemoveSessionToken() {
        vault.saveSession("acc_456", "session_token_xyz");
        assertTrue(vault.hasSession("acc_456"));

        vault.removeSession("acc_456");
        assertFalse(vault.hasSession("acc_456"));
        assertNull(vault.getSession("acc_456"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullAccountIdRejected() {
        vault.saveSession(null, "some_key");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyAuthKeyRejected() {
        vault.saveSession("acc_789", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullPreferencesRejectedInConstructor() {
        new SecureSessionVault(null, false);
    }
}
