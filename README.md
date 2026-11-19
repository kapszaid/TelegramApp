# NexGram - Nicegram-style Telegram Client with Proxy & Multi-Account

NexGram is a custom Telegram client built for power users, developers, and testers who need:
- **Proxy per Account (SOCKS5 / MTProto)** to prevent IP bans and avoid frozen accounts.
- **Multi-Account Manager** to switch between accounts instantly.
- **Export StringSession** for zero-OTP reuse in websites and scripts.
- **Full Telegram Features**: Chats, Groups, Channels, Search, and Real-time Messaging.
- **Mobile & Android Ready**: Responsive mobile-first UI installable as a standalone Android app (PWA).

---

## 🚀 Quick Start

1. Start the server:
   ```bash
   npm start
   ```
2. Open your browser:
   ```
   http://localhost:3000
   ```
   *(Or on your Android phone connected to the same Wi-Fi: `http://<your-pc-ip>:3000`)*

---

## 🛡️ How NexGram Prevents Frozen Accounts

### Why Accounts Get Frozen on Websites/Scripts:
1. **IP Flagging**: Telegram monitors repeated connections from datacenter IPs without proxy.
2. **Repeated Logins / Auth Flood**: Requesting OTPs multiple times on new IPs triggers Telegram's risk engine.
3. **FloodWait**: Sending bulk actions too quickly.

### How NexGram Solves This:
1. **Isolated SOCKS5 / MTProto Proxy**: Every account can have its own dedicated proxy with built-in latency testing.
2. **StringSession Generation**: Once you log in on NexGram, you can click **"Copy Session"** in the Accounts Manager and paste that session into your website backend. This avoids sending repeated OTPs and prevents verification bans.
3. **Persistent Session Storage**: Sessions are saved in `accounts.json` so you never lose connection.

---

## 📱 Using on Android (Mobile App Mode)

1. Open `http://<YOUR_PC_IP>:3000` on Chrome or Brave on your Android phone.
2. Tap the three dots (⋮) menu in Chrome.
3. Select **"Install App"** or **"Add to Home Screen"**.
4. NexGram will now launch in **Full Screen native app mode** directly from your phone's home screen!
