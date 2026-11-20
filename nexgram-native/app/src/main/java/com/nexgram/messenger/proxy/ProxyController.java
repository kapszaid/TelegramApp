package com.nexgram.messenger.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.nexgram.messenger.auth.SecureSessionVault;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Pattern;

/**
 * Native Proxy Controller for NexGram
 *
 * Manages SOCKS5 and MTProto configurations directly on the Android device.
 *
 * SECURITY & ARCHITECTURAL INVARIANTS:
 * 1. Proxy passwords and MTProto secret keys are NEVER stored in plaintext. They are encrypted
 *    via the SecureSessionVault (Android Keystore).
 * 2. Non-sensitive metadata (host, port, proxy type, enabled flag) are stored in standard SharedPreferences.
 * 3. Strict host/port validation prevents invalid, loopback, or out-of-range configurations.
 * 4. TCP socket reachability is explicitly separated from proxy authentication.
 *
 * NOTE ON OPERATIONAL STATUS:
 * Per-account proxy routing is a configuration layer at this stage. It will be wired to the
 * native Telegram MTProto socket engine when the protocol client milestone is implemented.
 */
public class ProxyController {
    private static final String PREF_NAME = "nexgram_proxies_meta";

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,6}$");

    public enum ProxyType {
        SOCKS5,
        MTPROTO
    }

    public enum ReachabilityStatus {
        TCP_REACHABLE,       // Socket connected at TCP layer (handshake/auth not yet verified)
        UNREACHABLE,         // Connection refused or network failure
        TIMEOUT,             // Socket connect timed out
        INVALID_CONFIGURATION
    }

    public static class ReachabilityResult {
        public final ReachabilityStatus status;
        public final long latencyMs;
        public final String note;

        public ReachabilityResult(ReachabilityStatus status, long latencyMs, String note) {
            this.status = status;
            this.latencyMs = latencyMs;
            this.note = note;
        }
    }

    /**
     * Non-sensitive proxy metadata stored in standard preferences
     */
    public static class ProxyMetadata {
        public ProxyType type = ProxyType.SOCKS5;
        public String host;
        public int port;
        public String username;
        public boolean enabled = false;
        public long lastLatencyMs = -1;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final SecureSessionVault sessionVault;
    private final Gson gson = new Gson();

    public ProxyController(Context context, SecureSessionVault sessionVault) {
        this.context = context;
        this.sessionVault = sessionVault;
        this.prefs = context != null ? context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) : null;
    }

    public ProxyController(SharedPreferences customPrefs, SecureSessionVault sessionVault) {
        this.context = null;
        this.sessionVault = sessionVault;
        this.prefs = customPrefs;
    }

    /**
     * Validates host and port according to networking standards.
     * Rejects empty hosts, localhost/loopback, and invalid ports.
     */
    public static void validate(String host, int port) throws IllegalArgumentException {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Proxy host address cannot be empty");
        }
        String cleanHost = host.trim().toLowerCase();
        if (cleanHost.equals("localhost") || cleanHost.equals("127.0.0.1") || cleanHost.equals("::1")) {
            throw new IllegalArgumentException("Localhost (127.0.0.1) is not a valid remote Telegram proxy");
        }
        if (!IPV4_PATTERN.matcher(cleanHost).matches() && !DOMAIN_PATTERN.matcher(cleanHost).matches()) {
            throw new IllegalArgumentException("Proxy host must be a valid IPv4 address or domain name: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Proxy port must be between 1 and 65535. Given: " + port);
        }
    }

    /**
     * Saves proxy configuration. Non-sensitive parts go to metadata;
     * sensitive password/secret are saved to the encrypted vault.
     */
    public synchronized void saveProxy(String accountId, ProxyMetadata meta, String secretOrPassword) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be empty");
        }
        if (meta == null) {
            throw new IllegalArgumentException("Proxy metadata cannot be null");
        }
        validate(meta.host, meta.port);

        // Save non-sensitive metadata
        prefs.edit().putString("meta_" + accountId, gson.toJson(meta)).apply();

        // Encrypt secret / password via SecureSessionVault
        if (sessionVault != null) {
            String key = "proxy_sec_" + accountId;
            if (secretOrPassword != null && !secretOrPassword.isEmpty()) {
                sessionVault.saveSession(key, secretOrPassword);
            } else {
                sessionVault.removeSession(key);
            }
        }
    }

    public synchronized ProxyMetadata getProxyMetadata(String accountId) {
        if (accountId == null) return null;
        String json = prefs.getString("meta_" + accountId, null);
        if (json != null) {
            return gson.fromJson(json, ProxyMetadata.class);
        }
        return null;
    }

    public synchronized String getProxySecret(String accountId) {
        if (accountId == null || sessionVault == null) return null;
        return sessionVault.getSession("proxy_sec_" + accountId);
    }

    public synchronized void setProxyEnabled(String accountId, boolean enabled) {
        ProxyMetadata meta = getProxyMetadata(accountId);
        if (meta != null) {
            meta.enabled = enabled;
            prefs.edit().putString("meta_" + accountId, gson.toJson(meta)).apply();
        }
    }

    public synchronized void removeProxy(String accountId) {
        if (accountId == null) return;
        prefs.edit().remove("meta_" + accountId).apply();
        if (sessionVault != null) {
            sessionVault.removeSession("proxy_sec_" + accountId);
        }
    }

    /**
     * Checks TCP socket connectivity to the proxy host/port.
     * Note: This measures TCP transport reachability only; it does NOT verify SOCKS5/MTProto authentication.
     */
    public ReachabilityResult testReachability(String host, int port, int timeoutMs) {
        try {
            validate(host, port);
        } catch (IllegalArgumentException e) {
            return new ReachabilityResult(ReachabilityStatus.INVALID_CONFIGURATION, -1, e.getMessage());
        }

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            long latency = System.currentTimeMillis() - start;
            return new ReachabilityResult(
                    ReachabilityStatus.TCP_REACHABLE,
                    latency,
                    "TCP socket reachable (" + latency + "ms). Note: MTProto authentication not yet verified."
            );
        } catch (java.net.SocketTimeoutException e) {
            return new ReachabilityResult(ReachabilityStatus.TIMEOUT, -1, "Connection timed out after " + timeoutMs + "ms");
        } catch (IOException e) {
            return new ReachabilityResult(ReachabilityStatus.UNREACHABLE, -1, "Socket unreachable: " + e.getMessage());
        }
    }
}
