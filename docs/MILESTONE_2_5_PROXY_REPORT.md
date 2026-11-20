# NexGram Milestone 2.5 — Account-Specific Proxy Manager Verification Report

**Date:** November 20, 2026  
**Repository:** `https://github.com/kapszaid/TelegramApp.git`  
**Branch:** `main`  
**Commits:**
- `f028d28` — Conversation UI & Navigation Bar Inset Overlap Fix
- `e5a2ce4` — Milestone 2.5: Account-Specific Proxy Manager with Native TDLib Integration  

---

## 1. Executive Summary

Milestone 2.5 successfully establishes an **Account-Specific Proxy Manager** with 100% native TDLib 1.8.70 integration:
1. **Preserved Milestone 2.4 & 2.4.1 functionality:** All chat list features, avatars, unread counts, and conversation screen improvements remain fully intact.
2. **Account Isolation:** Every Telegram account possesses an independent proxy configuration. Setting or switching Account 1's proxy has zero impact on Account 2.
3. **Pre-Login Proxy Selection:** Users can configure, ping-test, and enable a SOCKS5 or MTProto proxy in `AuthActivity` before submitting their phone number or authenticating.
4. **Post-Login Dynamic Switching:** Users can switch proxies (or revert to direct connection) in `SettingsActivity` while logged in without session termination, without logging out, and without requesting an OTP code.
5. **Protocol Support:** Supports Direct Connection, SOCKS5 (with username and password), and MTProto (with hex secret).
6. **Native Connectivity Verification:** Pings proxies directly through TDLib's native network stack via `TdApi.PingProxy` with real round-trip latency in milliseconds.
7. **Encrypted Storage:** Passwords and MTProto secret keys are encrypted in the hardware-backed Android Keystore (`SecureSessionVault` AES-256-GCM). Non-sensitive metadata is segregated into SharedPreferences per account.

---

## 2. Architecture & Security Invariants

### 2.1 Storage Segregation

| Data Element | Storage Location | Encryption / Protection |
|---|---|---|
| Proxy Type, Host, Port, Username, Enabled Flag, Latency | `SharedPreferences` (`meta_<accountId>`) | Isolated per account ID |
| SOCKS5 Password, MTProto Secret Key | `SecureSessionVault` (`proxy_sec_<accountId>`) | Hardware-backed Android Keystore (AES-256-GCM MasterKey) |
| Localhost / Loopback Addresses | Blocked at validation | Rejects `127.0.0.1`, `localhost`, `::1` |

### 2.2 Native TDLib Routing

- **Proxy Activation:** `client.send(new TdApi.AddProxy(proxy, true, comment))` registers the proxy and immediately sets it as active.
- **Direct Connection:** `client.send(new TdApi.DisableProxy())` disables any proxy and routes traffic directly.
- **Native Verification:** `client.send(new TdApi.PingProxy(proxy))` executes an official MTProto ping and returns `TdApi.Seconds` or `TdApi.Error`.
- **Session Preservation:** TDLib switches the transport socket internally while keeping the `TdApi.AuthorizationStateReady` session valid.

---

## 3. Test Results & Verification

All 41 unit tests executed and passed (100% success rate):

```
File     : TEST-com.nexgram.messenger.AccountManagerTest.xml     | Tests: 4  | Failures: 0 | Errors: 0
File     : TEST-com.nexgram.messenger.BackupCryptoTest.xml       | Tests: 6  | Failures: 0 | Errors: 0
File     : TEST-com.nexgram.messenger.ProxyControllerTest.xml    | Tests: 11 | Failures: 0 | Errors: 0
File     : TEST-com.nexgram.messenger.SecureSessionVaultTest.xml | Tests: 8  | Failures: 0 | Errors: 0
File     : TEST-com.nexgram.messenger.TdLibIntegrationTest.xml   | Tests: 12 | Failures: 0 | Errors: 0
Total: 41 Passed, 0 Failed, 0 Skipped
```

### Key Milestone 2.5 Tests:

1. **Germany-to-USA Proxy Switching without Session Loss (`testGermanyToUsaProxySwitchingWithoutSessionLoss`):**
   - Configures Germany SOCKS5 (`198.51.100.1:1080`).
   - Switches to USA MTProto (`198.51.100.2:443`).
   - Asserts that the Telegram auth session token in `SecureSessionVault` remains completely intact and byte-for-byte identical.
2. **Account 1 vs. Account 2 Isolation (`testMultiAccountProxyIsolationAccount1AndAccount2`):**
   - Configures Account 1 with Germany SOCKS5 and Account 2 with USA MTProto.
   - Reverts Account 1 to Direct connection.
   - Asserts Account 1 proxy is disabled while Account 2 proxy remains active with its intact secret.
3. **TDLib Proxy Class and Function Binding (`testTdLibProxyClassesAndFunctions`):**
   - Validates `TdApi.AddProxy`, `TdApi.EnableProxy`, `TdApi.DisableProxy`, `TdApi.PingProxy`, `TdApi.GetProxies`, `TdApi.ConnectionStateConnectingToProxy`.

---

## 4. Built APK Details

- **Output File:** `nexgram-native/app/build/outputs/apk/debug/app-debug.apk`
- **File Size:** 44,822,519 bytes (~44.8 MB)
- **Status:** Complete & verified.
