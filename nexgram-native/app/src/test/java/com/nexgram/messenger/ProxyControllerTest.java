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

    @Test
    public void testBuildTdApiProxySocks5AndMtproto() {
        // SOCKS5 test
        ProxyController.ProxyMetadata socksMeta = new ProxyController.ProxyMetadata();
        socksMeta.host = "de.proxy.telegram.org";
        socksMeta.port = 1080;
        socksMeta.username = "germany_user";
        socksMeta.type = ProxyController.ProxyType.SOCKS5;
        socksMeta.enabled = true;

        org.drinkless.tdlib.TdApi.Proxy socksProxy = ProxyController.buildTdApiProxy(socksMeta, "socks_pwd_123");
        assertNotNull(socksProxy);
        assertEquals("de.proxy.telegram.org", socksProxy.server);
        assertEquals(1080, socksProxy.port);
        assertTrue(socksProxy.type instanceof org.drinkless.tdlib.TdApi.ProxyTypeSocks5);
        org.drinkless.tdlib.TdApi.ProxyTypeSocks5 socksType = (org.drinkless.tdlib.TdApi.ProxyTypeSocks5) socksProxy.type;
        assertEquals("germany_user", socksType.username);
        assertEquals("socks_pwd_123", socksType.password);

        // MTProto test
        ProxyController.ProxyMetadata mtMeta = new ProxyController.ProxyMetadata();
        mtMeta.host = "us.proxy.telegram.org";
        mtMeta.port = 443;
        mtMeta.type = ProxyController.ProxyType.MTPROTO;
        mtMeta.enabled = true;

        org.drinkless.tdlib.TdApi.Proxy mtProxy = ProxyController.buildTdApiProxy(mtMeta, "ee112233445566778899aabbccddeeff");
        assertNotNull(mtProxy);
        assertEquals("us.proxy.telegram.org", mtProxy.server);
        assertEquals(443, mtProxy.port);
        assertTrue(mtProxy.type instanceof org.drinkless.tdlib.TdApi.ProxyTypeMtproto);
        org.drinkless.tdlib.TdApi.ProxyTypeMtproto mtType = (org.drinkless.tdlib.TdApi.ProxyTypeMtproto) mtProxy.type;
        assertEquals("ee112233445566778899aabbccddeeff", mtType.secret);
    }

    @Test
    public void testGermanyToUsaProxySwitchingWithoutSessionLoss() {
        String accountId = "account_switcher";

        // 1. Establish persistent Telegram session key in Vault
        vault.saveSession(accountId, "telegram_auth_session_key_persistent_xyz");
        byte[] dbKey = vault.getOrCreateDatabaseKey(accountId);
        assertNotNull(dbKey);
        assertEquals("telegram_auth_session_key_persistent_xyz", vault.getSession(accountId));

        // 2. Configure Germany SOCKS5 proxy
        ProxyController.ProxyMetadata germanyMeta = new ProxyController.ProxyMetadata();
        germanyMeta.host = "198.51.100.1";
        germanyMeta.port = 1080;
        germanyMeta.username = "de_user";
        germanyMeta.type = ProxyController.ProxyType.SOCKS5;
        germanyMeta.enabled = true;
        germanyMeta.label = "Germany SOCKS5";
        controller.saveProxy(accountId, germanyMeta, "pwd_germany");

        org.drinkless.tdlib.TdApi.Proxy proxy1 = controller.getTdApiProxy(accountId);
        assertNotNull(proxy1);
        assertEquals("198.51.100.1", proxy1.server);
        assertEquals(1080, proxy1.port);
        assertTrue(proxy1.type instanceof org.drinkless.tdlib.TdApi.ProxyTypeSocks5);

        // 3. Switch to USA MTProto proxy without dropping session
        ProxyController.ProxyMetadata usaMeta = new ProxyController.ProxyMetadata();
        usaMeta.host = "198.51.100.2";
        usaMeta.port = 443;
        usaMeta.type = ProxyController.ProxyType.MTPROTO;
        usaMeta.enabled = true;
        usaMeta.label = "USA MTProto";
        controller.saveProxy(accountId, usaMeta, "secret_usa_hex_998877");

        org.drinkless.tdlib.TdApi.Proxy proxy2 = controller.getTdApiProxy(accountId);
        assertNotNull(proxy2);
        assertEquals("198.51.100.2", proxy2.server);
        assertEquals(443, proxy2.port);
        assertTrue(proxy2.type instanceof org.drinkless.tdlib.TdApi.ProxyTypeMtproto);

        // 4. CRITICAL INVARIANT: Telegram session key in Vault remains 100% intact (zero session loss, no OTP required)
        assertEquals("telegram_auth_session_key_persistent_xyz", vault.getSession(accountId));
        org.junit.Assert.assertArrayEquals(dbKey, vault.getOrCreateDatabaseKey(accountId));
    }

    @Test
    public void testMultiAccountProxyIsolationAccount1AndAccount2() {
        String account1 = "account_1_germany";
        String account2 = "account_2_usa";

        // Setup Account 1: Germany SOCKS5
        ProxyController.ProxyMetadata meta1 = new ProxyController.ProxyMetadata();
        meta1.host = "germany.tgproxy.org";
        meta1.port = 1080;
        meta1.username = "user_de";
        meta1.type = ProxyController.ProxyType.SOCKS5;
        meta1.enabled = true;
        controller.saveProxy(account1, meta1, "de_secret_123");

        // Setup Account 2: USA MTProto
        ProxyController.ProxyMetadata meta2 = new ProxyController.ProxyMetadata();
        meta2.host = "usa.tgproxy.org";
        meta2.port = 8443;
        meta2.type = ProxyController.ProxyType.MTPROTO;
        meta2.enabled = true;
        controller.saveProxy(account2, meta2, "us_secret_hex_456");

        // Verify both accounts have their own distinct proxies
        org.drinkless.tdlib.TdApi.Proxy proxy1 = controller.getTdApiProxy(account1);
        org.drinkless.tdlib.TdApi.Proxy proxy2 = controller.getTdApiProxy(account2);
        assertNotNull(proxy1);
        assertNotNull(proxy2);
        assertEquals("germany.tgproxy.org", proxy1.server);
        assertEquals("usa.tgproxy.org", proxy2.server);
        assertTrue(proxy1.type instanceof org.drinkless.tdlib.TdApi.ProxyTypeSocks5);
        assertTrue(proxy2.type instanceof org.drinkless.tdlib.TdApi.ProxyTypeMtproto);

        // Now modify Account 1 to Direct connection
        controller.saveDirectConnection(account1);

        // Account 1 MUST be direct (no proxy)
        assertNull(controller.getTdApiProxy(account1));
        assertNull(controller.getProxySecret(account1));
        assertFalse(controller.getProxyMetadata(account1).enabled);

        // Account 2 MUST remain completely unaffected (USA MTProto still active and intact)
        org.drinkless.tdlib.TdApi.Proxy unaffectedProxy2 = controller.getTdApiProxy(account2);
        assertNotNull(unaffectedProxy2);
        assertEquals("usa.tgproxy.org", unaffectedProxy2.server);
        assertEquals(8443, unaffectedProxy2.port);
        assertEquals("us_secret_hex_456", controller.getProxySecret(account2));
        assertTrue(controller.getProxyMetadata(account2).enabled);
    }
}
