# NexGram Milestone 3.0 — Complete Multi-Account Management & Account Switching

**Date:** 2026-11-21  
**Project:** `C:\telegram app\nexgram-native`  
**Engine:** Native TDLib 1.8.70 (Pre-built AAR with full native MTProto binary)  
**Target:** Android 15 (API 35), Min SDK 26  

---

## 1. Executive Summary

Milestone 3.0 delivers complete, production-grade Telegram multi-account management and real-time account switching for NexGram. Built upon native TDLib 1.8.70, it reproduces the official Telegram Android multi-account architecture:
1. **Independent Account Sandboxing:** Every account runs its own isolated TDLib client instance, dedicated database directory (`files/tdlib_accounts/<accountId>`), dedicated 256-bit database encryption key in Android Keystore, independent proxy configuration, and isolated Telegram authorization state.
2. **Instant Account Switching:** Users can switch between authenticated Telegram accounts directly from the navigation drawer header dropdown with zero logout, zero session loss, and zero OTP re-prompting.
3. **Legacy Session Preservation:** The existing authenticated account (`account_main`) was meticulously preserved across all database schemas and Keystore vaults without database migration corruption.
4. **Isolated Proxy Management:** Each account maintains its own proxy preferences (Direct, SOCKS5, or MTProto) which are dynamically applied upon switching.
5. **Duplicate Account Detection:** Guard logic ensures the user cannot add duplicate sessions for the same Telegram account (by Telegram User ID or verified phone number).
6. **Isolated Account Removal / Logout:** Logging out or removing an account strictly wipes that specific account's database and Keystore credentials without touching other active sessions.

---

## 2. Technical Architecture & Data Isolation

### 2.1 File System & Storage Isolation
Each account is assigned a unique, stable ID (`account_<hex12>` or `account_main`). TDLib files are completely segregated:
```
/data/user/0/com.nexgram.messenger/files/
└── tdlib_accounts/
    ├── account_main/
    │   ├── td.binlog
    │   └── files/
    ├── account_a1b2c3d4e5f6/
    │   ├── td.binlog
    │   └── files/
    └── account_9876543210ab/
        ├── td.binlog
        └── files/
```

### 2.2 Android Keystore Database Encryption Key Isolation
- `SecureSessionVault` generates and secures a cryptographically independent 32-byte AES key per account alias: `"tdlib_db_key_" + accountId`.
- Even if two accounts run on the same device, their database keys are unique and hardware-protected under Android Keystore's MasterKey.

### 2.3 Per-Account Proxy Isolation
- Stored under `meta_<accountId>` in SharedPreferences and encrypted secrets under `proxy_sec_<accountId>` in `SecureSessionVault`.
- When switching active accounts, `MainActivity` dynamically queries and applies the target account's saved proxy configuration to its TDLib client via `activeClient.applySavedProxy(proxyController, null)`.

---

## 3. UI Implementation Details

### 3.1 Telegram Android Drawer Header
- **Profile Header:** Clicking the header or dropdown chevron (`ivNavDropdown`) toggles the animated account switcher list.
- **Account List (`rvDrawerAccounts`):** Displays all registered Telegram accounts with circular avatar (initials with Telegram palette or photo), display name, international phone number, and active checkmark (`ic_check`).
- **Account Switching:** Single tap switches active account, closes the drawer, clears old chat caches, attaches listeners to the new account's TDLib client, applies proxy, and loads chats.
- **Account Removal:** Long press prompts a confirmation dialog to log out and remove that specific account.
- **Add Account Action (`btnNavAddAccount`):** Launches `AuthActivity` with a freshly generated stable account ID (`account_<uuid_12>`), leaving current sessions running in the background.

---

## 4. Verification & Test Suite

The test suite in `TdLibIntegrationTest.java` was expanded with multi-account integration tests:
1. `testAccountRegistryPersistenceAndSwitching`: Verifies registration of multiple accounts, active account selection, switching, and cold-restart persistence.
2. `testStableAccountIdGeneration`: Verifies generation of unique, non-colliding account IDs.
3. `testMultiAccountDirectoryAndKeyIsolation`: Verifies cryptographic distinctness of 32-byte encryption keys and database paths.
4. `testAccountRemovalIsolation`: Verifies that removing one account wipes its session in the vault while other accounts remain 100% intact.
5. `testPerAccountProxyIsolation`: Verifies Germany SOCKS5 and USA MTProto proxy isolation between Account 1 and Account 2.
6. `testDuplicateAccountDetection`: Verifies duplicate user detection by Telegram User ID and phone number.

### Test Execution Output:
```
> Task :app:compileDebugUnitTestJavaWithJavac
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 55s
42 actionable tasks: 5 executed, 37 up-to-date
```

### Generated Build Artifact:
- **APK Path:** `nexgram-native/app/build/outputs/apk/debug/app-debug.apk`
- **Size:** 43,398,677 bytes (43.4 MB)
- **Status:** Verified and signed with debug keystore.
