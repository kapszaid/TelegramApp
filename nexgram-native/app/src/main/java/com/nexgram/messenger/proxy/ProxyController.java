package com.nexgram.messenger.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Native Proxy Controller for NexGram
 * Supports SOCKS5 and MTProto configurations directly on the Android device.
 */
public class ProxyController {
    private static final String PREF_NAME = "nexgram_proxies";

    public enum ProxyType {
        SOCKS5,
        MTPROTO
    }

    public static class ProxyConfig {
        public ProxyType type = ProxyType.SOCKS5;
        public String host;
        public int port;
        public String username;
        public String password;
        public String secret; // For MTProto
        public boolean enabled = false;
        public long pingMs = -1;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public ProxyController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveProxy(String accountId, ProxyConfig config) {
        prefs.edit().putString("proxy_" + accountId, gson.toJson(config)).apply();
    }

    public ProxyConfig getProxy(String accountId) {
        String json = prefs.getString("proxy_" + accountId, null);
        if (json != null) {
            return gson.fromJson(json, ProxyConfig.class);
        }
        return null;
    }

    public void setProxyEnabled(String accountId, boolean enabled) {
        ProxyConfig config = getProxy(accountId);
        if (config != null) {
            config.enabled = enabled;
            saveProxy(accountId, config);
        }
    }

    /**
     * Measure network socket latency directly from Android device
     */
    public long testLatency(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return System.currentTimeMillis() - start;
        } catch (IOException e) {
            return -1;
        }
    }
}
