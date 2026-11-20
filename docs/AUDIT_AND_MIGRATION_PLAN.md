# NexGram: Architecture Audit & Native Android Migration Plan

## 1. Executive Summary & Existing Project Audit

### 1.1 Current Architecture Overview
The current repository (`TelegramApp`) contains an early web-based prototype with a hybrid mobile wrapper:
- **Presentation Layer (`public/index.html`, `public/app.css`, `public/app.js`):** A single-page application mimicking Telegram Android's UI (drawer, chat list, chat view, sub-settings).
- **Backend Bridge Layer (`server.js`):** A Node.js Express server running GramJS (v2.26.22) that connects to Telegram's MTProto servers over TCP/WebSocket.
- **Data & Session Persistence (`accounts.json`, `config.js`):** Account metadata and raw Telegram `StringSession` strings stored unencrypted in a server-side JSON file.
- **Android Shell (`android/`, Capacitor v8.6.2):** An Android project wrapping `public/` inside an Android `WebView`. In `capacitor.config.json`, the configuration points to `http://192.168.0.240:3000`.

```
[CURRENT FLAWED ARCHITECTURE]
Android Device (WebView)
      │
      ▼  (HTTP / WebSocket over local Wi-Fi)
PC Node.js Server (server.js @ 192.168.0.240:3000)
      ├── accounts.json (Plaintext StringSessions)
      │
      ▼  (GramJS MTProto TCP)
Telegram Cloud Servers
```

### 1.2 Critical Architectural Deficiencies
1. **PC Host / LAN Dependency:** The Android app cannot function independently outdoors on cellular data or outside the developer's Wi-Fi network without the PC server running.
2. **Session Decoupling:** User authentication sessions are retained on the PC server instead of being securely housed within the Android device's hardware-backed Keystore.
3. **Absence of Real Native Device Integrations:** Background push notifications (FCM), native background synchronization (`WorkManager`), system notification badges, hardware camera/microphone access for VoIP calls, and native file provider sharing are missing or non-functional.
4. **Placeholder Implementations:** Several UI features (e.g., profile photo changes, custom privacy settings, offline caching, and voice playback) only mutate local DOM elements or browser `localStorage`, lacking backend protocol execution.

### 1.3 Security & Privacy Vulnerabilities Identified
- **Plaintext Session Storage:** `accounts.json` stores full Telegram authentication keys (`StringSession`) in unencrypted plaintext on disk.
- **Exposed Session Endpoints:** `/api/accounts` and `/api/status` expose session strings and user auth data without token authentication or mutual TLS.
- **Clipboard Session Leakage:** The prototype offered UI buttons allowing users to copy raw `StringSession` data directly into the system clipboard.
- **Open CORS Policy:** `server.js` enables open CORS (`cors()`), allowing any malicious web script running in the user's browser to make requests to the local Node.js Telegram bridge.

---

## 2. Target Native Architecture

### 2.1 Core Architectural Principles
The target architecture migrates NexGram from a hybrid web-wrapper into a **100% standalone, native Android application**:
- **Pure Native Execution:** Built in Java/Kotlin using the Android SDK and official native Telegram client engine (`TMessagesProj` / TDLib MTProto core).
- **Device-Direct Networking:** All MTProto handshakes, AES-IGE encryption, and media chunk streaming occur directly between the phone and Telegram's data centers (`DC1`–`DC5`). Zero Node.js or intermediary proxy servers required.
- **Hardware-Backed Session Security:** Account authorization keys are encrypted using AES-256-GCM with keys stored in the Android Keystore system.
- **Zero Insecure Session Exports:** No plaintext strings exposed; portable backup utilizes password-derived AES-GCM archives exported via the Android Storage Access Framework (SAF).

```
[TARGET NATIVE ARCHITECTURE]
┌──────────────────────────────────────────────────────────┐
│                   NexGram Android App                    │
│                                                          │
│  ┌────────────────────┐      ┌─────────────────────────┐ │
│  │   NexGram UI       │      │  Multi-Account Manager  │ │
│  │ (Android Views/Jet)│      │  (Native Switcher)      │ │
│  └─────────┬──────────┘      └───────────┬─────────────┘ │
│            │                             │               │
│            ▼                             ▼               │
│  ┌─────────────────────────────────────────────────────┐ │
│  │    Native Telegram Messaging Engine (MTProto Core)  │ │
│  │    - Socket Management & Keepalive                  │ │
│  │    - SOCKS5 / MTProto Proxy Routing                 │ │
│  │    - SQLite Local Database & Media Cache            │ │
│  └─────────────────────────┬───────────────────────────┘ │
│                            │                             │
│  ┌─────────────────────────▼───────────────────────────┐ │
│  │  Android Security & OS Layer                        │ │
│  │  - Android Keystore (Encrypted Auth Tokens)         │ │
│  │  - WorkManager (Background Sync)                    │ │
│  │  - Native Notifications & Media Services            │ │
│  └─────────────────────────────────────────────────────┘ │
└────────────────────────────┬─────────────────────────────┘
                             │
                             ▼ Direct MTProto (TCP/TLS)
              Telegram Cloud Data Centers (DCs 1-5)
```

---

## 3. Preservation of Prototype & Migration Strategy

To adhere to the user's requirements:
1. **Source Preservation:** The existing prototype files (`server.js`, `public/`, `android/` Capacitor project, `package.json`) remain completely untouched in the repository for historical reference.
2. **Dedicated Native Project Directory:** The native application is established in a dedicated standalone root directory: `nexgram-native/`.
3. **Android Studio Ready:** `nexgram-native/` contains standard Gradle build files (`build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`) so it can be opened immediately in Android Studio without conflicting with the legacy prototype.

---

## 4. Milestone 1 Deliverables & Execution Plan

### Milestone 1 Goals:
1. **Audit & Migration Plan:** Completed and committed to `docs/AUDIT_AND_MIGRATION_PLAN.md`.
2. **Native Project Foundation:** Scaffold the standalone `nexgram-native` project with standard Android directory structures (`app/src/main/java`, `res/`, `AndroidManifest.xml`).
3. **Branding & Identity:**
   - Package Name: `com.nexgram.messenger`
   - Application Name: `NexGram`
   - Colors: Primary NexGram Blue (`#2AABEE` / `#1E2C3A`), Dark Theme palette (`#17212B`, `#0E1621`).
   - Clear compliance notice: "NexGram is an independent messaging client using the Telegram API."
4. **Build Configuration:**
   - Configure Gradle with compileSdk 35/36, minSdk 24 (Android 7.0+), Java 17 compatibility.
   - Set up API credential configuration via `gradle.properties` / `local.properties` (zero hardcoded secrets in source control).
5. **Compilation Verification:** Execute Gradle build (`gradlew assembleDebug`) and verify generated APK artifact.
