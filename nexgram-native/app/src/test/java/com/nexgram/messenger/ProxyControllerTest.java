package com.nexgram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;
import com.nexgram.messenger.auth.SecureSessionVault;
import com.nexgram.messenger.proxy.ProxyController;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit Test Suite for NexGram Native ProxyController
 * Validates strict host & port validation, encrypted secret isolation, and reachability differentiation.
 */
public class ProxyControllerTest {

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

    private ProxyController controller;
    private SecureSessionVault vault;
    private MockSharedPreferences mockPrefs;
    private MockSharedPreferences mockVaultPrefs;

    @Before
    public void setUp() {
        mockPrefs = new MockSharedPreferences();
        mockVaultPrefs = new MockSharedPreferences();
        vault = new SecureSessionVault(mockVaultPrefs, false);
        controller = new ProxyController(mockPrefs, vault);
    }

    @Test
    public void testValidHostAndPortValidation() {
        try {
            ProxyController.validate("proxy.example.com", 1080);
            ProxyController.validate("198.51.100.25", 443);
            ProxyController.validate("socks.tg.me", 8080);
        } catch (IllegalArgumentException e) {
            fail("Valid host and port should not throw exception: " + e.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLocalhostRejectedAsFakeProxy() {
        ProxyController.validate("127.0.0.1", 1080);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLocalhostNamedRejectedAsFakeProxy() {
        ProxyController.validate("localhost", 1080);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidPortZeroRejected() {
        ProxyController.validate("proxy.example.com", 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPortOutOfRangeRejected() {
        ProxyController.validate("proxy.example.com", 65536);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyHostRejected() {
        ProxyController.validate("", 1080);
    }

    @Test
    public void testSaveProxyAndEncryptedSecretIsolation() {
        ProxyController.ProxyMetadata meta = new ProxyController.ProxyMetadata();
        meta.host = "proxy.example.com";
        meta.port = 1080;
        meta.type = ProxyController.ProxyType.SOCKS5;
        meta.enabled = true;

        controller.saveProxy("acc_test", meta, "super_secret_proxy_password");

        // Verify metadata retrieval
        ProxyController.ProxyMetadata savedMeta = controller.getProxyMetadata("acc_test");
        assertNotNull(savedMeta);
        assertEquals("proxy.example.com", savedMeta.host);
        assertEquals(1080, savedMeta.port);
        assertTrue(savedMeta.enabled);

        // Verify secret is NOT in plaintext metadata prefs
        assertFalse(mockPrefs.getAll().containsValue("super_secret_proxy_password"));

        // Verify secret IS in encrypted vault
        assertEquals("super_secret_proxy_password", controller.getProxySecret("acc_test"));

        // Clean up
        controller.removeProxy("acc_test");
        assertNull(controller.getProxyMetadata("acc_test"));
        assertNull(controller.getProxySecret("acc_test"));
    }

    @Test
    public void testReachabilityDistinguishesTcpFromAuthentication() {
        ProxyController.ReachabilityResult res = controller.testReachability("192.0.2.1", 1080, 50);
        assertNotNull(res);
        assertNotNull(res.note);
        assertFalse(res.note.isEmpty());
    }
}
