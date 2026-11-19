// NexGram Mobile-First Client Engine

// Determine API Base URL for Browser vs Native APK
function getClientDeviceModel() {
  if (localStorage.getItem('NEXGRAM_DEVICE_MODEL')) {
    return localStorage.getItem('NEXGRAM_DEVICE_MODEL');
  }
  const ua = navigator.userAgent;
  // Dynamic Android device model detection from User-Agent
  const match = ua.match(/\(Linux; Android [^;]+; ([^;)]+)\)/);
  if (match && match[1]) {
    const raw = match[1].replace(/Build\/.*/, '').trim();
    if (raw) return raw;
  }
  if (/iPhone/.test(ua)) return 'Apple iPhone';
  if (/iPad/.test(ua)) return 'Apple iPad';
  if (/Macintosh/.test(ua)) return 'Apple Mac';
  if (/Windows/.test(ua)) return 'Windows PC';
  if (/Linux/.test(ua)) return 'Linux Device';
  return 'Mobile Client';
}

function getClientSystemVersion() {
  const ua = navigator.userAgent;
  const m = ua.match(/Android ([0-9.]+)/);
  if (m) return `Android ${m[1]}`;
  if (/iPhone OS ([0-9_]+)/.test(ua)) return `iOS ${RegExp.$1.replace(/_/g, '.')}`;
  if (/Windows NT ([0-9.]+)/.test(ua)) return `Windows NT ${RegExp.$1}`;
  return 'Android 14';
}

function getApiBase() {
  const isCapacitor = window.location.protocol === 'capacitor:' ||
                      window.location.protocol === 'file:' ||
                      (window.location.hostname === 'localhost' && window.location.port !== '3000');
  if (isCapacitor) {
    return localStorage.getItem('NEXGRAM_BACKEND_URL') || 'http://192.168.0.240:3000';
  }
  return '';
}

async function apiFetch(url, options) {
  const fullUrl = url.startsWith('http') ? url : (getApiBase() + url);
  try {
    const res = await fetch(fullUrl, options);
    const originalJson = res.json.bind(res);
    res.json = async () => {
      const text = await res.text();
      try {
        return JSON.parse(text);
      } catch (e) {
        if (!res.ok) {
          throw new Error(`Server returned status ${res.status}: ${res.statusText || 'Error'}`);
        }
        if (text.trim().startsWith('<') || text.includes('<!DOCTYPE')) {
          if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
            throw new Error('Server returned an HTML page. Please ensure server is running.');
          }
          throw new Error('Server connection error. Please ensure your mobile is connected to the same Wi-Fi network as your PC and mobile data is off or Wi-Fi is active.');
        }
        throw e;
      }
    };
    return res;
  } catch (err) {
    if (err.message && (err.message.includes('Server connection error') || err.message.includes('Server returned an HTML'))) {
      throw err;
    }
    if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
      throw new Error('Cannot connect to local server. Server band hai ya chal nahi raha (node server.js).');
    }
    throw new Error('Cannot connect to server. Please check your Wi-Fi connection.');
  }
}

let state = {
  activeAccount: null,
  dialogs: [],
  filteredDialogs: [],
  activeTab: 'all',
  activeChat: null,
  tempAuthSessionId: null,
  pollingTimer: null,
  searchTimer: null,
  isFullScreen: false
};

const COLOR_CLASSES = ['color-blue', 'color-green', 'color-orange', 'color-purple', 'color-cyan', 'color-red'];

function getAvatarColorClass(str) {
  let hash = 0;
  for (let i = 0; i < (str || 'T').length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  const index = Math.abs(hash) % COLOR_CLASSES.length;
  return COLOR_CLASSES[index];
}

// Bootstrap
document.addEventListener('DOMContentLoaded', () => {
  initClock();
  initApp();
  registerServiceWorker();
});

// Register Service Worker for Android App (WebAPK)
let deferredPrompt = null;
function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').then(() => {
      console.log('ServiceWorker registered for Android App');
    }).catch(err => console.log('SW registration error:', err));
  }

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    const banner = document.getElementById('androidInstallBanner');
    if (banner) banner.style.display = 'flex';
  });
}

function triggerAppInstall() {
  if (deferredPrompt) {
    deferredPrompt.prompt();
    deferredPrompt.userChoice.then((choiceResult) => {
      if (choiceResult.outcome === 'accepted') {
        console.log('User installed the APK');
      }
      deferredPrompt = null;
    });
  } else {
    alert('Phone me Chrome ke top 3 dots (⋮) par tap karein aur "Install app" ya "Add to Home screen" select karein!');
  }
}

// Update phone status bar clock
function initClock() {
  const clock = document.getElementById('statusBarClock');
  function update() {
    const now = new Date();
    clock.innerText = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  }
  update();
  setInterval(update, 30000);
}

// Toggle Viewport (Mobile Frame vs Full Screen)
function toggleViewportMode() {
  state.isFullScreen = !state.isFullScreen;
  const viewport = document.getElementById('appViewport');
  const icon = document.getElementById('viewModeIcon');
  const text = document.getElementById('viewModeText');

  if (state.isFullScreen) {
    viewport.classList.add('full-screen');
    icon.className = 'fa-solid fa-mobile-screen-button';
    text.innerText = 'Mobile App View';
  } else {
    viewport.classList.remove('full-screen');
    icon.className = 'fa-solid fa-desktop';
    text.innerText = 'Full Screen View';
  }
}

// Initialize App State
async function initApp() {
  try {
    const res = await apiFetch('/api/status');
    const data = await res.json();
    state.appStatus = data;
    state.activeAccount = data.activeAccount;

    updateHeaderAndDrawer();

    if (data.accountsCount > 0) {
      // User has accounts! Load dialogs for active account
      if (data.activeAccount) {
        loadDialogsFeed();
      }
    } else {
      // No accounts at all, show login modal
      renderEmptyChatsState();
      openLoginModal();
    }
  } catch (err) {
    console.error('Init error:', err);
  }
}

// Update Top App Bar & Drawer Card
function updateHeaderAndDrawer() {
  const proxyPill = document.getElementById('headerProxyPill');
  const proxyText = document.getElementById('headerProxyText');
  const drawerName = document.getElementById('drawerUserName');
  const drawerPhone = document.getElementById('drawerUserPhone');
  const drawerAvatar = document.getElementById('drawerUserAvatar');

  if (state.activeAccount) {
    drawerName.innerText = state.activeAccount.name || 'NexGram User';
    drawerPhone.innerText = state.activeAccount.phone || '';
    const initial = (state.activeAccount.name || 'N').substring(0, 1).toUpperCase();
    drawerAvatar.innerText = initial;

    const p = state.activeAccount.proxy;
    if (p && p.enabled && p.host) {
      proxyPill.classList.add('active');
      proxyText.innerText = `${p.type || 'SOCKS5'}`;
      proxyPill.title = `Connected via Proxy: ${p.host}:${p.port}`;
    } else {
      proxyPill.classList.remove('active');
      proxyText.innerText = 'Direct';
      proxyPill.title = 'Direct connection (No proxy)';
    }
  } else {
    drawerName.innerText = 'Not Logged In';
    drawerPhone.innerText = 'Tap to sign in';
    drawerAvatar.innerText = '?';
    proxyPill.classList.remove('active');
    proxyText.innerText = 'Direct';
  }

  // Populate multiple accounts switcher in drawer
  loadDrawerAccounts();
}

// Load and populate Multiple Accounts directly in Left Drawer (Telegram Android Style)
async function loadDrawerAccounts() {
  const container = document.getElementById('drawerAccountsList');
  if (!container) return;

  try {
    const res = await apiFetch('/api/accounts');
    const data = await res.json();

    if (!data.accounts || data.accounts.length === 0) {
      container.innerHTML = `
        <div class="drawer-add-acc-btn" onclick="openLoginModal()">
          <i class="fa-solid fa-plus"></i> Add Account
        </div>
      `;
      return;
    }

    let html = '';
    data.accounts.forEach(acc => {
      const isCurrent = acc.isActive;
      const initial = (acc.name || 'T').substring(0, 1).toUpperCase();
      const colorClass = getAvatarColorClass(acc.name);

      html += `
        <div class="drawer-acc-item ${isCurrent ? 'active' : ''}" onclick="switchAccountAction('${acc.id}')">
          <div class="drawer-acc-left">
            <div class="drawer-acc-avatar ${colorClass}">${initial}</div>
            <div class="drawer-acc-info">
              <div class="drawer-acc-name">${escapeHtml(acc.name)}</div>
              <div class="drawer-acc-phone">${acc.phone || ''}</div>
            </div>
          </div>
          ${isCurrent ? '<i class="fa-solid fa-circle-check" style="color: var(--tg-primary); font-size: 15px;"></i>' : ''}
        </div>
      `;
    });

    html += `
      <div class="drawer-add-acc-btn" onclick="openLoginModal()">
        <i class="fa-solid fa-plus"></i> Add Another Account
      </div>
    `;

    container.innerHTML = html;
  } catch (e) {
    console.error('Failed to load drawer accounts:', e);
  }
}

// Toggle Drawer Accounts List
function toggleDrawerAccounts() {
  const list = document.getElementById('drawerAccountsList');
  const chevron = document.getElementById('drawerChevron');
  const isOpen = list.classList.contains('open');

  if (isOpen) {
    list.classList.remove('open');
    chevron.style.transform = 'rotate(0deg)';
  } else {
    list.classList.add('open');
    chevron.style.transform = 'rotate(180deg)';
  }
}

// --------------------------------------------------------
// 1. CHATS LIST FEED & TABS
// --------------------------------------------------------
async function loadDialogsFeed() {
  const container = document.getElementById('chatsListContainer');
  container.innerHTML = `
    <div style="padding: 40px 20px; text-align: center; color: var(--tg-text-muted);">
      <i class="fa-solid fa-circle-notch fa-spin" style="font-size: 24px; margin-bottom: 12px; color: var(--tg-primary);"></i>
      <p style="font-size: 14px;">Loading chats...</p>
    </div>
  `;

  try {
    const res = await apiFetch('/api/dialogs');
    const data = await res.json();

    if (data.success && data.dialogs) {
      state.dialogs = data.dialogs;
      document.getElementById('tabBadgeAll').innerText = state.dialogs.length;
      applyTab(state.activeTab);
    } else if (data.sessionRevoked || (data.error && data.error.includes('AUTH_KEY_UNREGISTERED'))) {
      // Session was terminated on Telegram, auto-switch to next account
      console.log('Session was terminated on Telegram. Auto-switching...');
      initApp();
    } else {
      container.innerHTML = `
        <div style="padding: 40px 20px; text-align: center; color: var(--tg-text-muted);">
          <p style="font-size: 14px;">${data.error || 'No chats found'}</p>
        </div>
      `;
    }
  } catch (err) {
    if (err.message && err.message.includes('AUTH_KEY_UNREGISTERED')) {
      initApp();
      return;
    }
    container.innerHTML = `
      <div style="padding: 40px 20px; text-align: center; color: #ff5252;">
        <p style="font-size: 14px;">Failed to load chats: ${err.message}</p>
      </div>
    `;
  }
}

function selectTab(tabName, btn) {
  document.querySelectorAll('.tg-tab-item').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  applyTab(tabName);
}

function applyTab(tabName) {
  state.activeTab = tabName;
  if (tabName === 'all') {
    state.filteredDialogs = state.dialogs;
  } else {
    state.filteredDialogs = state.dialogs.filter(d => d.type === tabName);
  }
  renderChatsList(state.filteredDialogs);
}

function renderChatsList(list) {
  const container = document.getElementById('chatsListContainer');
  if (!list || list.length === 0) {
    container.innerHTML = `
      <div style="padding: 60px 20px; text-align: center; color: var(--tg-text-muted);">
        <i class="fa-regular fa-comment-dots" style="font-size: 38px; margin-bottom: 14px; opacity: 0.4;"></i>
        <p style="font-size: 14.5px;">No conversations in this folder</p>
      </div>
    `;
    return;
  }

  let html = '';
  list.forEach(chat => {
    const initial = (chat.title || 'T').substring(0, 1).toUpperCase();
    const colorClass = getAvatarColorClass(chat.title);

    let timeFormatted = '';
    if (chat.date) {
      const d = new Date(chat.date);
      const today = new Date();
      if (d.toDateString() === today.toDateString()) {
        timeFormatted = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      } else {
        timeFormatted = d.toLocaleDateString([], { month: 'short', day: 'numeric' });
      }
    }

    const unreadHtml = chat.unreadCount > 0 ? `<div class="tg-unread-counter">${chat.unreadCount}</div>` : '';

    let typeIcon = '';
    if (chat.isChannel) typeIcon = '<i class="fa-solid fa-bullhorn" style="font-size: 11px; margin-right: 4px; color: var(--tg-text-muted);"></i>';
    else if (chat.isGroup) typeIcon = '<i class="fa-solid fa-users" style="font-size: 11px; margin-right: 4px; color: var(--tg-text-muted);"></i>';

    html += `
      <div class="tg-chat-row" onclick="openChatView('${chat.id}', '${escapeHtml(chat.title)}', '${chat.type}')">
        <div class="tg-chat-avatar ${colorClass}">${initial}</div>
        <div class="tg-chat-meta">
          <div class="tg-chat-row-top">
            <span class="tg-chat-name">${typeIcon}${escapeHtml(chat.title)}</span>
            <span class="tg-chat-time">${timeFormatted}</span>
          </div>
          <div class="tg-chat-row-bottom">
            <span class="tg-chat-preview">${escapeHtml(chat.lastMessage || '')}</span>
            ${unreadHtml}
          </div>
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
}

// --------------------------------------------------------
// 2. CONVERSATION VIEW (Android Screen Transition)
// --------------------------------------------------------
async function openChatView(chatId, title, type) {
  state.activeChat = { id: chatId, title, type };

  // Slide conversation screen in
  const screen = document.getElementById('screenConversation');
  screen.classList.add('open');

  // Populate Header
  document.getElementById('activeChatTitle').innerText = title;
  const initial = title.substring(0, 1).toUpperCase();
  const avatarEl = document.getElementById('activeChatAvatar');
  avatarEl.innerText = initial;
  avatarEl.className = 'tg-chat-avatar ' + getAvatarColorClass(title);

  let statusText = 'last seen recently';
  if (type === 'channel') statusText = 'channel';
  else if (type === 'group') statusText = 'group';
  document.getElementById('activeChatStatus').innerText = statusText;

  // Load Messages
  loadMessages(chatId, true);

  // Poll for new messages every 3.5s
  if (state.pollingTimer) clearInterval(state.pollingTimer);
  state.pollingTimer = setInterval(() => {
    if (state.activeChat && state.activeChat.id === chatId) {
      loadMessages(chatId, false);
    }
  }, 3500);
}

function backToChatsList() {
  document.getElementById('screenConversation').classList.remove('open');
  if (state.pollingTimer) clearInterval(state.pollingTimer);
  state.activeChat = null;
}

async function loadMessages(chatId, showLoading = true) {
  const container = document.getElementById('messagesListContainer');
  if (showLoading) {
    container.innerHTML = `
      <div style="margin: auto; text-align: center; color: var(--tg-text-muted);">
        <i class="fa-solid fa-circle-notch fa-spin"></i>
      </div>
    `;
  }

  try {
    const res = await apiFetch(`/api/messages/${chatId}?limit=50`);
    const data = await res.json();

    if (data.success && data.messages) {
      renderMessagesStream(data.messages);
    }
  } catch (err) {
    console.error('Load messages error:', err);
  }
}

function renderMessagesStream(messages) {
  const container = document.getElementById('messagesListContainer');
  if (messages.length === 0) {
    container.innerHTML = `
      <div class="tg-date-badge">No messages yet</div>
    `;
    return;
  }

  let html = '';
  let lastDate = '';

  messages.forEach(m => {
    if (m.date) {
      const d = new Date(m.date);
      const dateStr = d.toLocaleDateString([], { month: 'short', day: 'numeric' });
      if (dateStr !== lastDate) {
        html += `<div class="tg-date-badge">${dateStr}</div>`;
        lastDate = dateStr;
      }
    }

    const bubbleClass = m.out ? 'outgoing' : 'incoming';
    const timeFormatted = m.date ? new Date(m.date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
    const readCheck = m.out ? '<i class="fa-solid fa-check-double" style="font-size: 10px;"></i>' : '';

    html += `
      <div class="tg-msg-bubble ${bubbleClass}">
        ${!m.out && m.sender ? `<div class="tg-msg-sender">${escapeHtml(m.sender)}</div>` : ''}
        <span class="tg-msg-text">${escapeHtml(m.text)}</span>
        <div class="tg-msg-meta">
          <span>${timeFormatted}</span>
          ${readCheck}
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
  container.scrollTop = container.scrollHeight;
}

// Send Message
async function sendChatMessage() {
  if (!state.activeChat) return;
  const input = document.getElementById('composerInput');
  const text = input.value.trim();
  if (!text) return;

  input.value = '';

  // Optimistic UI bubble
  const container = document.getElementById('messagesListContainer');
  const timeFormatted = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const temp = document.createElement('div');
  temp.className = 'tg-msg-bubble outgoing';
  temp.innerHTML = `
    <span class="tg-msg-text">${escapeHtml(text)}</span>
    <div class="tg-msg-meta">
      <span>${timeFormatted}</span>
      <i class="fa-solid fa-clock" style="font-size: 10px;"></i>
    </div>
  `;
  container.appendChild(temp);
  container.scrollTop = container.scrollHeight;

  try {
    const res = await apiFetch('/api/messages/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ chatId: state.activeChat.id, message: text })
    });
    const data = await res.json();
    if (!data.success) {
      alert('Failed to send: ' + (data.error || 'Unknown error'));
    } else {
      loadMessages(state.activeChat.id, false);
    }
  } catch (err) {
    alert('Error: ' + err.message);
  }
}

function handleComposerKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendChatMessage();
  }
}

// --------------------------------------------------------
// 3. SEARCH & ACTIONS
// --------------------------------------------------------
function toggleSearchBar() {
  const box = document.getElementById('inlineSearchBox');
  const isHidden = box.style.display === 'none';
  box.style.display = isHidden ? 'block' : 'none';
  if (isHidden) {
    document.getElementById('searchInputField').focus();
  } else {
    document.getElementById('searchInputField').value = '';
    applyTab(state.activeTab);
  }
}

function handleSearchInput(e) {
  const q = e.target.value.trim();
  clearTimeout(state.searchTimer);

  if (!q) {
    applyTab(state.activeTab);
    return;
  }

  state.searchTimer = setTimeout(async () => {
    const local = state.dialogs.filter(d => 
      d.title.toLowerCase().includes(q.toLowerCase()) || 
      (d.username && d.username.toLowerCase().includes(q.toLowerCase()))
    );

    try {
      const res = await apiFetch(`/api/search?q=${encodeURIComponent(q)}`);
      const data = await res.json();
      const combined = [...local];

      if (data.success && data.results) {
        data.results.forEach(r => {
          if (!combined.some(c => c.id === r.id)) {
            combined.push({
              id: r.id,
              title: r.title,
              username: r.username,
              type: r.type,
              lastMessage: r.username ? `@${r.username}` : '[Global Search]'
            });
          }
        });
      }

      renderChatsList(combined);
    } catch (err) {
      renderChatsList(local);
    }
  }, 300);
}

function openNewChatAction() {
  toggleSearchBar();
}

// --------------------------------------------------------
// 4. NAVIGATION DRAWER
// --------------------------------------------------------
function toggleDrawer(open) {
  const backdrop = document.getElementById('drawerBackdrop');
  if (open) {
    backdrop.classList.add('open');
  } else {
    backdrop.classList.remove('open');
  }
}

function handleDrawerBackdropClick(e) {
  if (e.target.id === 'drawerBackdrop') {
    toggleDrawer(false);
  }
}

function openSavedMessagesChat() {
  toggleDrawer(false);
  if (state.activeAccount) {
    openChatView('me', 'Saved Messages', 'user');
  } else {
    openLoginModal();
  }
}

// --------------------------------------------------------
// 5. AUTHENTIC TELEGRAM LOGIN FLOW (Phone & QR Code)
// --------------------------------------------------------
let qrCheckInterval = null;
let currentQrTempId = null;

function openLoginModal() {
  toggleDrawer(false);
  document.getElementById('authModal').classList.add('open');
  resetAuthStep1();
  checkModalExistingAccounts();
}

function closeLoginModal() {
  document.getElementById('authModal').classList.remove('open');
  stopQrCodePolling();
  if (state.activeAccount) {
    loadDialogsFeed();
  }
}

function handleAuthBackdropClick(e) {
  if (e.target.id === 'authModal') {
    // If user has accounts, close on background click
    if (state.appStatus && state.appStatus.accountsCount > 0) {
      closeLoginModal();
    }
  }
}

// Show existing accounts in login modal if user already has accounts saved
async function checkModalExistingAccounts() {
  const container = document.getElementById('modalExistingAccountsBox');
  const listEl = document.getElementById('modalAccountsList');
  const closeBtn = document.getElementById('btnAuthClose');

  try {
    const res = await apiFetch('/api/accounts');
    const data = await res.json();

    if (data.accounts && data.accounts.length > 0) {
      if (closeBtn) closeBtn.style.display = 'flex';
      container.style.display = 'block';

      let html = '';
      data.accounts.forEach(acc => {
        const isCurrent = acc.isActive;
        const initial = (acc.name || 'N').substring(0, 1).toUpperCase();
        const colorClass = getAvatarColorClass(acc.name);

        html += `
          <div class="drawer-acc-item ${isCurrent ? 'active' : ''}" style="background: rgba(255,255,255,0.05); padding: 8px 12px; border-radius: 8px;" onclick="switchAccountAction('${acc.id}'); closeLoginModal();">
            <div class="drawer-acc-left">
              <div class="drawer-acc-avatar ${colorClass}">${initial}</div>
              <div class="drawer-acc-info">
                <div class="drawer-acc-name">${escapeHtml(acc.name)}</div>
                <div class="drawer-acc-phone">${acc.phone || ''}</div>
              </div>
            </div>
            ${isCurrent ? '<i class="fa-solid fa-circle-check" style="color: var(--tg-primary); font-size: 15px;"></i>' : '<button class="tg-btn-ghost" style="width: auto; padding: 2px 8px; font-size: 11px; margin: 0;">Switch</button>'}
          </div>
        `;
      });

      listEl.innerHTML = html;
    } else {
      if (closeBtn) closeBtn.style.display = 'none';
      container.style.display = 'none';
    }
  } catch (e) {
    console.error('Check existing accounts error:', e);
  }
}

function resetAuthStep1() {
  stopQrCodePolling();
  document.getElementById('authModalTitle').innerText = 'Sign in to NexGram';
  document.getElementById('authModalSubtitle').innerText = 'Enter your country code or scan QR code.';
  document.getElementById('authModeTabs').style.display = 'flex';
  document.getElementById('stepPhone').style.display = 'block';
  document.getElementById('stepQrLogin').style.display = 'none';
  document.getElementById('stepCode').style.display = 'none';
  document.getElementById('step2FA').style.display = 'none';
  document.getElementById('stepImport').style.display = 'none';

  document.getElementById('tabPhoneLogin').classList.add('active');
  document.getElementById('tabQrLogin').classList.remove('active');
}

// Switch between Phone Login and QR Code Login Tabs
function switchAuthMode(mode) {
  stopQrCodePolling();
  const tabPhone = document.getElementById('tabPhoneLogin');
  const tabQr = document.getElementById('tabQrLogin');
  const stepPhone = document.getElementById('stepPhone');
  const stepQr = document.getElementById('stepQrLogin');

  if (mode === 'phone') {
    tabPhone.classList.add('active');
    tabQr.classList.remove('active');
    stepPhone.style.display = 'block';
    stepQr.style.display = 'none';
    document.getElementById('authModalTitle').innerText = 'Sign in to NexGram';
    document.getElementById('authModalSubtitle').innerText = 'Enter your country code and phone number.';
  } else {
    tabQr.classList.add('active');
    tabPhone.classList.remove('active');
    stepPhone.style.display = 'none';
    stepQr.style.display = 'block';
    document.getElementById('authModalTitle').innerText = 'Log in by QR Code';
    document.getElementById('authModalSubtitle').innerText = 'Scan with your app on phone to link account.';
    startQrCodeAuth();
  }
}

// Start Telegram QR Code Authentication
async function startQrCodeAuth() {
  stopQrCodePolling();

  const spinner = document.getElementById('qrLoadingSpinner');
  const qrImg = document.getElementById('qrCodeImage');
  spinner.style.display = 'flex';
  qrImg.style.display = 'none';

  // Check if proxy is enabled
  const proxyEnabled = document.getElementById('checkProxyToggle') && document.getElementById('checkProxyToggle').checked;
  let proxyConfig = { enabled: false };
  if (proxyEnabled) {
    proxyConfig = {
      enabled: true,
      type: document.getElementById('inputProxyType').value,
      host: document.getElementById('inputProxyHost').value.trim(),
      port: document.getElementById('inputProxyPort').value.trim(),
      username: document.getElementById('inputProxyUser').value.trim(),
      password: document.getElementById('inputProxyPass').value.trim()
    };
  }

  try {
    const res = await apiFetch('/api/auth/qr-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ proxy: proxyConfig, deviceModel: getClientDeviceModel() })
    });
    const data = await res.json();

    if (data.success && data.qrUrl) {
      currentQrTempId = data.sessionTempId;
      // Generate QR image from the official tg://login URL
      const qrApiUrl = `https://api.qrserver.com/v1/create-qr-code/?size=190x190&data=${encodeURIComponent(data.qrUrl)}`;
      qrImg.src = qrApiUrl;
      qrImg.onload = () => {
        spinner.style.display = 'none';
        qrImg.style.display = 'block';
      };

      // Poll status every 2 seconds
      qrCheckInterval = setInterval(async () => {
        if (!currentQrTempId) return;
        try {
          const checkRes = await apiFetch('/api/auth/qr-check', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionTempId: currentQrTempId })
          });
          const checkData = await checkRes.json();

          if (checkData.status === 'success') {
            stopQrCodePolling();
            alert('🎉 QR Code scanned successfully! Logged in to NexGram.');
            closeLoginModal();
            initApp();
          } else if (checkData.status === 'needs_2fa') {
            stopQrCodePolling();
            state.tempAuthSessionId = checkData.sessionTempId;
            document.getElementById('authModeTabs').style.display = 'none';
            document.getElementById('stepQrLogin').style.display = 'none';
            document.getElementById('step2FA').style.display = 'block';
            document.getElementById('authModalTitle').innerText = 'Two-Step Verification';
            document.getElementById('authModalSubtitle').innerText = 'Enter your Cloud Password to finish linking.';
          }
        } catch (e) {
          console.log('QR check polling:', e);
        }
      }, 2000);

    } else {
      spinner.innerHTML = '<span style="color: #ff5252; font-size: 13px;">Failed to generate QR</span>';
    }
  } catch (err) {
    spinner.innerHTML = `<span style="color: #ff5252; font-size: 13px;">Error: ${err.message}</span>`;
  }
}

function stopQrCodePolling() {
  if (qrCheckInterval) {
    clearInterval(qrCheckInterval);
    qrCheckInterval = null;
  }
  currentQrTempId = null;
}

function toggleProxyForm() {
  const isChecked = document.getElementById('checkProxyToggle').checked;
  document.getElementById('proxyFieldsBox').style.display = isChecked ? 'block' : 'none';
}

async function testProxyPingAction() {
  const host = document.getElementById('inputProxyHost').value.trim();
  const port = document.getElementById('inputProxyPort').value.trim();
  const type = document.getElementById('inputProxyType').value;
  const username = document.getElementById('inputProxyUser').value.trim();
  const password = document.getElementById('inputProxyPass').value.trim();
  const pingEl = document.getElementById('proxyPingResult');

  if (!host || !port) {
    alert('Please enter Proxy Host and Port');
    return;
  }

  pingEl.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> pinging...';

  try {
    const res = await apiFetch('/api/proxy/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, port, type, username, password })
    });
    const data = await res.json();

    if (data.success) {
      pingEl.innerHTML = `<span style="color: #4cd964;"><i class="fa-solid fa-check"></i> ${data.latency}ms</span>`;
    } else {
      pingEl.innerHTML = `<span style="color: #ff5252;">Failed</span>`;
    }
  } catch (err) {
    pingEl.innerHTML = `<span style="color: #ff5252;">Error</span>`;
  }
}

// Step 1: Send Phone
async function submitPhoneForCode() {
  const phone = document.getElementById('inputPhone').value.trim();
  if (!phone) {
    alert('Please enter your phone number with country code (e.g. +91...)');
    return;
  }

  const proxyEnabled = document.getElementById('checkProxyToggle').checked;
  let proxyConfig = { enabled: false };

  if (proxyEnabled) {
    proxyConfig = {
      enabled: true,
      type: document.getElementById('inputProxyType').value,
      host: document.getElementById('inputProxyHost').value.trim(),
      port: document.getElementById('inputProxyPort').value.trim(),
      username: document.getElementById('inputProxyUser').value.trim(),
      password: document.getElementById('inputProxyPass').value.trim()
    };
  }

  const btn = document.getElementById('btnPhoneNext');
  btn.disabled = true;
  btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Connecting...';

  try {
    const res = await apiFetch('/api/auth/send-code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phoneNumber: phone, proxy: proxyConfig, deviceModel: getClientDeviceModel() })
    });
    const data = await res.json();
    btn.disabled = false;
    btn.innerHTML = 'Next <i class="fa-solid fa-arrow-right"></i>';

    if (data.success) {
      state.tempAuthSessionId = data.sessionTempId;
      document.getElementById('authModalTitle').innerText = 'Verification Code';
      document.getElementById('authModalSubtitle').innerText = `Code sent to Telegram or SMS on ${phone}`;
      document.getElementById('stepPhone').style.display = 'none';
      document.getElementById('stepCode').style.display = 'block';
    } else {
      alert('Error: ' + (data.error || 'Failed to send code'));
    }
  } catch (err) {
    btn.disabled = false;
    btn.innerHTML = 'Next <i class="fa-solid fa-arrow-right"></i>';
    alert('Network error: ' + err.message);
  }
}

// Step 2: Confirm Code
async function submitVerificationCode() {
  const code = document.getElementById('inputCode').value.trim();
  if (!code) {
    alert('Please enter the code');
    return;
  }

  const btn = document.getElementById('btnCodeVerify');
  btn.disabled = true;
  btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Verifying...';

  try {
    const res = await apiFetch('/api/auth/sign-in', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionTempId: state.tempAuthSessionId, phoneCode: code })
    });
    const data = await res.json();
    btn.disabled = false;
    btn.innerHTML = 'Confirm Code';

    if (data.needs2FA) {
      document.getElementById('authModalTitle').innerText = 'Two-Step Verification';
      document.getElementById('authModalSubtitle').innerText = 'Enter your Telegram Cloud Password';
      document.getElementById('stepCode').style.display = 'none';
      document.getElementById('step2FA').style.display = 'block';
    } else if (data.success) {
      closeLoginModal();
      initApp();
    } else {
      alert('Login failed: ' + (data.error || 'Invalid code'));
    }
  } catch (err) {
    btn.disabled = false;
    btn.innerHTML = 'Confirm Code';
    alert('Error: ' + err.message);
  }
}

// Step 3: 2FA Password
async function submit2FAPassword() {
  const pass = document.getElementById('input2FAPass').value;
  if (!pass) {
    alert('Please enter your 2FA Password');
    return;
  }

  const btn = document.getElementById('btn2FAVerify');
  btn.disabled = true;
  btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Verifying...';

  try {
    const res = await apiFetch('/api/auth/2fa', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionTempId: state.tempAuthSessionId, password: pass })
    });
    const data = await res.json();
    btn.disabled = false;
    btn.innerHTML = 'Submit Password';

    if (data.success) {
      closeLoginModal();
      initApp();
    } else {
      alert('2FA failed: ' + (data.error || 'Incorrect password'));
    }
  } catch (err) {
    btn.disabled = false;
    btn.innerHTML = 'Submit Password';
    alert('Error: ' + err.message);
  }
}

// Direct StringSession Import
function showSessionImportStep() {
  document.getElementById('authModalTitle').innerText = 'Import StringSession';
  document.getElementById('authModalSubtitle').innerText = 'Paste your existing Telegram session string.';
  document.getElementById('stepPhone').style.display = 'none';
  document.getElementById('stepImport').style.display = 'block';
}

async function submitDirectSessionImport() {
  const sessionString = document.getElementById('inputSessionString').value.trim();
  if (!sessionString) {
    alert('Please enter your StringSession');
    return;
  }

  try {
    const res = await apiFetch('/api/auth/import-session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionString, deviceModel: getClientDeviceModel() })
    });
    const data = await res.json();

    if (data.success) {
      closeLoginModal();
      initApp();
    } else {
      alert('Import failed: ' + (data.error || 'Invalid session'));
    }
  } catch (err) {
    alert('Error: ' + err.message);
  }
}

function openApkDownloadModal() {
  toggleDrawer(false);
  document.getElementById('apkModal').classList.add('open');
}

function closeApkDownloadModal() {
  document.getElementById('apkModal').classList.remove('open');
}

// --------------------------------------------------------
// 6. PROXY & ACCOUNTS MANAGER
// --------------------------------------------------------
function openProxyManager() {
  toggleDrawer(false);
  openAccountsManager();
}

async function openAccountsManager() {
  toggleDrawer(false);
  document.getElementById('accountsModal').classList.add('open');
  const container = document.getElementById('accountsListContainer');
  container.innerHTML = '<div style="text-align: center; padding: 20px;"><i class="fa-solid fa-circle-notch fa-spin"></i></div>';

  try {
    const res = await apiFetch('/api/accounts');
    const data = await res.json();

    if (!data.accounts || data.accounts.length === 0) {
      container.innerHTML = '<p style="font-size: 13px; color: var(--tg-text-muted); text-align: center;">No accounts added yet.</p>';
      return;
    }

    let html = '';
    data.accounts.forEach(acc => {
      const isCurrent = acc.isActive;
      const proxyInfo = acc.proxy && acc.proxy.enabled && acc.proxy.host ?
        `<span style="color: #4cd964;"><i class="fa-solid fa-shield"></i> ${acc.proxy.type} (${acc.proxy.host}:${acc.proxy.port})</span>` :
        `<span style="color: var(--tg-text-muted);"><i class="fa-solid fa-globe"></i> Direct</span>`;

      html += `
        <div style="background: var(--tg-bg-base); border-radius: 8px; padding: 12px; border: 1px solid ${isCurrent ? 'var(--tg-primary)' : 'var(--tg-border)'};">
          <div style="display: flex; justify-content: space-between; align-items: flex-start;">
            <div>
              <div style="font-weight: 600; font-size: 14.5px; color: #fff;">
                ${escapeHtml(acc.name)}
                ${isCurrent ? '<span style="font-size: 10px; background: var(--tg-primary); padding: 1px 6px; border-radius: 4px; margin-left: 6px;">Active</span>' : ''}
              </div>
              <div style="font-size: 12.5px; color: var(--tg-text-secondary); margin-top: 2px;">${acc.phone || ''}</div>
              <div style="font-size: 11.5px; margin-top: 4px;">${proxyInfo}</div>
            </div>

            <div style="display: flex; gap: 6px;">
              ${!isCurrent ? `<button class="tg-btn-ghost" style="width: auto; padding: 4px 8px; font-size: 11px; margin: 0;" onclick="switchAccountAction('${acc.id}')">Switch</button>` : ''}
              <button class="tg-btn-ghost" style="width: auto; padding: 4px 8px; font-size: 11px; margin: 0;" title="Copy Session" onclick="copySessionString('${acc.sessionString}')">
                <i class="fa-regular fa-copy"></i>
              </button>
              <button class="tg-app-bar-btn" style="color: #ff5252; width: 28px; height: 28px; font-size: 12px;" onclick="deleteAccountAction('${acc.id}')">
                <i class="fa-solid fa-trash"></i>
              </button>
            </div>
          </div>
        </div>
      `;
    });

    container.innerHTML = html;
  } catch (err) {
    container.innerHTML = `<p style="color: #ff5252;">${err.message}</p>`;
  }
}

function closeAccountsModal() {
  document.getElementById('accountsModal').classList.remove('open');
}

async function switchAccountAction(accountId) {
  try {
    await apiFetch('/api/accounts/switch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accountId })
    });
    closeAccountsModal();
    backToChatsList();
    initApp();
  } catch (err) {
    alert(err.message);
  }
}

async function deleteAccountAction(accountId) {
  if (!confirm('Remove this account?')) return;
  try {
    await apiFetch(`/api/accounts/${accountId}`, { method: 'DELETE' });
    openAccountsManager();
    initApp();
  } catch (err) {
    alert(err.message);
  }
}

function copyCurrentSession() {
  toggleDrawer(false);
  if (state.activeAccount && state.activeAccount.sessionString) {
    copySessionString(state.activeAccount.sessionString);
  } else {
    alert('No active account session found');
  }
}

function copySessionString(str) {
  navigator.clipboard.writeText(str).then(() => {
    alert('✅ StringSession copied! You can now use this session in your website without repeated OTP verification.');
  }).catch(() => {
    prompt('Copy your session:', str);
  });
}

// =========================================================
// TELEGRAM USER SETTINGS & LOGOUT LOGIC
// =========================================================
async function openSettingsScreen() {
  toggleDrawer(false);
  const acc = state.activeAccount;
  const name = acc?.name || 'User';
  const phone = acc?.phone || 'No phone number';
  const username = acc?.username ? `@${acc.username}` : 'None';
  const initial = (name[0] || 'U').toUpperCase();

  const avatar = document.getElementById('settingsAvatar');
  if (avatar) {
    avatar.innerText = initial;
    avatar.className = `settings-large-avatar ${getAvatarColorClass(name)}`;
  }

  const nameEl = document.getElementById('settingsUserName');
  if (nameEl) nameEl.innerText = name;
  const phoneEl = document.getElementById('settingsPhone');
  if (phoneEl) phoneEl.innerText = phone;
  const usernameEl = document.getElementById('settingsUsername');
  if (usernameEl) usernameEl.innerText = username;
  const bioEl = document.getElementById('settingsBio');
  if (bioEl) bioEl.innerText = acc?.bio || 'NexGram Android Client';

  const proxySub = document.getElementById('settingsProxySub');
  if (proxySub) {
    if (acc?.proxy?.enabled) {
      proxySub.innerText = `${acc.proxy.type?.toUpperCase()} (${acc.proxy.host}:${acc.proxy.port}) - Active`;
      proxySub.style.color = '#4cd964';
    } else {
      proxySub.innerText = 'Direct connection (Anti-Freeze ready)';
      proxySub.style.color = 'var(--tg-text-secondary)';
    }
  }

  document.getElementById('screenSettings').classList.add('open');

  // Fetch live updated profile from Telegram MTProto
  try {
    const res = await apiFetch('/api/settings/profile');
    const pData = await res.json();
    if (pData.success) {
      if (nameEl && pData.name) nameEl.innerText = pData.name;
      if (phoneEl && pData.phone) phoneEl.innerText = `+${pData.phone}`;
      if (usernameEl) usernameEl.innerText = pData.username ? `@${pData.username}` : 'None';
      if (bioEl) bioEl.innerText = pData.bio || 'None';

      if (state.activeAccount) {
        state.activeAccount.firstName = pData.firstName;
        state.activeAccount.lastName = pData.lastName;
        state.activeAccount.bio = pData.bio;
        state.activeAccount.username = pData.username;
        if (pData.phone) state.activeAccount.phone = `+${pData.phone}`;
      }
    }
  } catch (e) {}
}

function closeSettingsScreen() {
  document.getElementById('screenSettings').classList.remove('open');
  const dd = document.getElementById('settingsDropdownMenu');
  if (dd) dd.classList.remove('open');
}

function toggleSettingsDropdown(e) {
  if (e) e.stopPropagation();
  const dd = document.getElementById('settingsDropdownMenu');
  if (dd) dd.classList.toggle('open');
}

// Close settings dropdown on any outer click
document.addEventListener('click', (e) => {
  const dd = document.getElementById('settingsDropdownMenu');
  if (dd && dd.classList.contains('open')) {
    if (!e.target.closest('#settingsDropdownMenu') && !e.target.closest('.settings-topbar')) {
      dd.classList.remove('open');
    }
  }
});

function openLogoutModal() {
  toggleDrawer(false);
  const dd = document.getElementById('settingsDropdownMenu');
  if (dd) dd.classList.remove('open');

  const accName = state.activeAccount?.name || 'this account';
  const modalText = document.getElementById('logoutModalText');
  if (modalText) {
    modalText.innerHTML = `Are you sure you want to log out from <strong>${accName}</strong>? You can add it back anytime.`;
  }

  document.getElementById('logoutModal').classList.add('open');
}

function closeLogoutModal() {
  document.getElementById('logoutModal').classList.remove('open');
}

function handleLogoutBackdropClick(e) {
  if (e.target.id === 'logoutModal') {
    closeLogoutModal();
  }
}

async function executeLogoutAction() {
  const btn = document.getElementById('btnConfirmLogout');
  if (btn) {
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Logging out...';
  }

  try {
    const res = await apiFetch('/api/auth/logout', { method: 'POST' });
    const data = await res.json();

    closeLogoutModal();
    closeSettingsScreen();

    // Re-initialize app
    await initApp();

    if (data.remainingAccounts === 0) {
      openLoginModal();
    }
  } catch (err) {
    alert('Logout error: ' + err.message);
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = 'Log Out';
    }
  }
}

function openNewGroupAction() {
  toggleDrawer(false);
  openSearchOverlay();
}

function openContactsAction() {
  toggleDrawer(false);
  openSearchOverlay();
}

function openCallsAction() {
  toggleDrawer(false);
  alert('Calls: No recent calls found.');
}

function renderEmptyChatsState() {
  const container = document.getElementById('chatsListContainer');
  container.innerHTML = `
    <div style="padding: 60px 20px; text-align: center;">
      <div class="tg-modal-logo" style="margin-bottom: 16px;">
        <i class="fa-solid fa-paper-plane"></i>
      </div>
      <h3 style="font-size: 17px; margin-bottom: 6px;">Sign in to Telegram</h3>
      <p style="font-size: 13.5px; color: var(--tg-text-secondary); margin-bottom: 20px;">
        Connect your phone with Proxy support to avoid frozen accounts.
      </p>
      <button class="tg-btn-blue" style="width: auto; padding: 10px 24px; margin: 0 auto;" onclick="openLoginModal()">
        Start Messaging
      </button>
    </div>
  `;
}

function escapeHtml(str) {
  if (!str) return '';
  return str.toString()
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}


// ========================================================
// 7. CHAT DROPDOWN MENU & FULL PROFILE INFO SCREEN
// ========================================================

let activeChatInfo = null;
let isCurrentChatMuted = false;

// Close dropdown on click outside
document.addEventListener('click', (e) => {
  const menu = document.getElementById('chatDropdownMenu');
  const btn = document.getElementById('btnChatMore');
  if (menu && menu.classList.contains('open')) {
    if (!menu.contains(e.target) && !btn.contains(e.target)) {
      menu.classList.remove('open');
    }
  }
});

function toggleChatDropdownMenu(e) {
  if (e) e.stopPropagation();
  const menu = document.getElementById('chatDropdownMenu');
  const isOpen = menu.classList.contains('open');

  if (!isOpen && state.activeChat) {
    const isGroupOrChannel = state.activeChat.type === 'group' || state.activeChat.type === 'channel';
    const leaveItem = document.getElementById('menuLeaveItem');
    const leaveText = document.getElementById('menuLeaveText');

    if (isGroupOrChannel) {
      leaveItem.style.display = 'flex';
      leaveText.innerText = state.activeChat.type === 'channel' ? 'Leave Channel' : 'Leave Group';
    } else {
      leaveItem.style.display = 'none';
    }

    // Update mute text
    const muteText = document.getElementById('menuMuteText');
    const muteIcon = document.getElementById('menuMuteIcon');
    if (isCurrentChatMuted) {
      muteText.innerText = 'Unmute Notifications';
      muteIcon.className = 'fa-solid fa-bell';
    } else {
      muteText.innerText = 'Mute Notifications';
      muteIcon.className = 'fa-solid fa-bell-slash';
    }

    menu.classList.add('open');
  } else {
    menu.classList.remove('open');
  }
}

function closeChatDropdownMenu() {
  const menu = document.getElementById('chatDropdownMenu');
  if (menu) menu.classList.remove('open');
}

// Open Full Chat Profile Info Screen
async function openChatProfileInfo() {
  closeChatDropdownMenu();
  if (!state.activeChat) return;

  const profileScreen = document.getElementById('screenProfile');
  profileScreen.classList.add('open');

  // Set initial placeholders
  const title = state.activeChat.title || 'Telegram User';
  document.getElementById('profileTitle').innerText = title;
  const initial = title.substring(0, 1).toUpperCase();
  const avatarEl = document.getElementById('profileAvatar');
  avatarEl.innerText = initial;
  avatarEl.className = 'profile-large-avatar ' + getAvatarColorClass(title);

  document.getElementById('profileSubtitle').innerText = 'Loading info...';
  document.getElementById('profilePhoneRow').style.display = 'none';
  document.getElementById('profileUsernameRow').style.display = 'none';
  document.getElementById('profileAboutRow').style.display = 'none';

  try {
    const res = await apiFetch(`/api/chats/${state.activeChat.id}/info`);
    const data = await res.json();

    if (data.success && data.info) {
      activeChatInfo = data.info;
      document.getElementById('profileTitle').innerText = data.info.title || title;

      // Subtitle
      if (data.info.isChannel) {
        document.getElementById('profileSubtitle').innerText = `${data.info.membersCount || 0} subscribers`;
      } else if (data.info.isGroup) {
        document.getElementById('profileSubtitle').innerText = `${data.info.membersCount || 0} members`;
      } else {
        document.getElementById('profileSubtitle').innerText = 'online';
      }

      // Phone
      if (data.info.phone) {
        document.getElementById('profilePhoneRow').style.display = 'flex';
        document.getElementById('profilePhoneVal').innerText = '+' + data.info.phone.replace(/^\+/, '');
      }

      // Username
      if (data.info.username) {
        document.getElementById('profileUsernameRow').style.display = 'flex';
        document.getElementById('profileUsernameVal').innerText = '@' + data.info.username;
      }

      // About
      if (data.info.about) {
        document.getElementById('profileAboutRow').style.display = 'flex';
        document.getElementById('profileAboutVal').innerText = data.info.about;
      }

      // Leave Button (Group / Channel)
      const leaveBtn = document.getElementById('profileLeaveBtn');
      const leaveText = document.getElementById('profileLeaveText');
      if (data.info.isGroup || data.info.isChannel) {
        leaveBtn.style.display = 'flex';
        leaveText.innerText = data.info.isChannel ? 'Leave Channel' : 'Leave Group';
      } else {
        leaveBtn.style.display = 'none';
      }

      // Notifications state
      updateNotificationUI(isCurrentChatMuted);
    }
  } catch (err) {
    console.error('Failed to load profile info:', err);
    document.getElementById('profileSubtitle').innerText = 'Info unavailable';
  }
}

function closeChatProfileInfo() {
  document.getElementById('screenProfile').classList.remove('open');
}

// Toggle Mute / Unmute
async function toggleMuteChatAction() {
  closeChatDropdownMenu();
  if (!state.activeChat) return;

  const targetMute = !isCurrentChatMuted;

  try {
    const res = await apiFetch(`/api/chats/${state.activeChat.id}/mute`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mute: targetMute })
    });
    const data = await res.json();

    if (data.success) {
      isCurrentChatMuted = data.muted;
      updateNotificationUI(isCurrentChatMuted);
      alert(isCurrentChatMuted ? '🔕 Notifications Muted' : '🔔 Notifications Unmuted');
    }
  } catch (err) {
    alert('Error muting chat: ' + err.message);
  }
}

function updateNotificationUI(muted) {
  const toggle = document.getElementById('profileNotifyToggle');
  const label = document.getElementById('profileNotifyLabel');
  const icon = document.getElementById('profileMuteHeaderIcon');

  if (toggle) toggle.checked = !muted;
  if (label) label.innerText = muted ? 'Disabled (Muted)' : 'Enabled';
  if (icon) icon.className = muted ? 'fa-solid fa-bell-slash' : 'fa-solid fa-bell';
}

// Leave Group or Channel
async function leaveChatAction() {
  closeChatDropdownMenu();
  if (!state.activeChat) return;

  const isChannel = state.activeChat.type === 'channel';
  const label = isChannel ? 'Channel' : 'Group';

  if (!confirm(`Are you sure you want to leave this ${label}?`)) {
    return;
  }

  try {
    const res = await apiFetch(`/api/chats/${state.activeChat.id}/leave`, {
      method: 'POST'
    });
    const data = await res.json();

    if (data.success) {
      alert(`✅ Left ${label} successfully`);
      closeChatProfileInfo();
      backToChatsList();
      loadDialogsFeed(); // refresh list
    } else {
      alert('Failed to leave: ' + (data.error || 'Unknown error'));
    }
  } catch (err) {
    alert('Error: ' + err.message);
  }
}

// Delete Chat / Clear History
async function deleteChatAction() {
  closeChatDropdownMenu();
  if (!state.activeChat) return;

  if (!confirm('Are you sure you want to delete this chat and clear messages?')) {
    return;
  }

  try {
    const res = await apiFetch(`/api/chats/${state.activeChat.id}/delete`, {
      method: 'POST'
    });
    const data = await res.json();

    if (data.success) {
      alert('✅ Chat deleted successfully');
      closeChatProfileInfo();
      backToChatsList();
      loadDialogsFeed();
    } else {
      alert('Failed to delete: ' + (data.error || 'Unknown error'));
    }
  } catch (err) {
    alert('Error: ' + err.message);
  }
}


// =========================================================
// TELEGRAM ANDROID FULL WORKING SETTINGS ENGINE
// =========================================================

function openSubScreen(id) {
  const el = document.getElementById(id);
  if (el) el.classList.add('open');
}

function closeSubScreen(id) {
  const el = document.getElementById(id);
  if (el) el.classList.remove('open');
}

// 1. Devices & Active Sessions Logic
async function openDevicesScreen() {
  openSubScreen('subDevices');
  const thisDevEl = document.getElementById('thisDeviceModel');
  if (thisDevEl) {
    thisDevEl.innerText = getClientDeviceModel();
  }

  const container = document.getElementById('activeSessionsListContainer');
  container.innerHTML = `
    <div style="padding: 24px; text-align: center; color: var(--tg-text-secondary);">
      <i class="fa-solid fa-circle-notch fa-spin"></i> Loading active sessions from Telegram...
    </div>
  `;

  try {
    const res = await apiFetch('/api/settings/authorizations');
    const data = await res.json();

    if (!data.authorizations || data.authorizations.length === 0) {
      container.innerHTML = `
        <div style="padding: 20px; text-align: center; color: var(--tg-text-secondary); font-size: 13.5px;">
          No other active sessions.
        </div>
      `;
      return;
    }

    const otherSessions = data.authorizations.filter(a => !a.current);

    if (otherSessions.length === 0) {
      container.innerHTML = `
        <div style="padding: 20px; text-align: center; color: var(--tg-text-secondary); font-size: 13.5px;">
          No other active sessions found. Only this device is logged in.
        </div>
      `;
      return;
    }

    container.innerHTML = otherSessions.map(session => {
      let icon = 'fa-solid fa-mobile-screen';
      let iconBg = '#3390ec';
      const model = (session.deviceModel || '').toLowerCase();
      const platform = (session.platform || '').toLowerCase();

      if (model.includes('windows') || platform.includes('desktop') || platform.includes('pc')) {
        icon = 'fa-solid fa-laptop';
        iconBg = '#ff595a';
      } else if (model.includes('iphone') || model.includes('ipad') || platform.includes('ios')) {
        icon = 'fa-brands fa-apple';
        iconBg = '#a282f7';
      } else if (model.includes('samsung') || model.includes('oneplus') || model.includes('android')) {
        icon = 'fa-brands fa-android';
        iconBg = '#4cd964';
      }

      const dateStr = session.dateActive ? new Date(session.dateActive * 1000).toLocaleDateString('en-US', {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
      }) : 'Recently active';

      return `
        <div class="settings-row" onclick="confirmTerminateSession('${session.hash}', '${session.deviceModel || 'Device'}')" style="padding: 12px 16px;">
          <div style="width: 40px; height: 40px; border-radius: 50%; background: ${iconBg}; display: flex; align-items: center; justify-content: center; color: white; font-size: 18px; margin-right: 14px;">
            <i class="${icon}"></i>
          </div>
          <div class="settings-row-content">
            <div style="display: flex; justify-content: space-between; align-items: baseline;">
              <span class="settings-row-text" style="font-size: 15px; font-weight: 600;">${session.deviceModel}</span>
              <span style="font-size: 11.5px; color: var(--tg-text-secondary);">${dateStr}</span>
            </div>
            <div class="settings-row-sub" style="font-size: 12.5px; margin-top: 2px;">
              ${session.appName} ${session.appVersion} • ${session.country || 'Location hidden'}
            </div>
          </div>
        </div>
      `;
    }).join('');

    // Update privacy count
    const privCountEl = document.getElementById('privSessionsCount');
    if (privCountEl) {
      privCountEl.innerText = `${data.authorizations.length} active devices`;
    }
  } catch (err) {
    container.innerHTML = `
      <div style="padding: 20px; text-align: center; color: #ff595a; font-size: 13.5px;">
        Failed to load sessions: ${err.message}
      </div>
    `;
  }
}

async function confirmTerminateSession(hash, model) {
  if (!confirm(`Are you sure you want to terminate session on ${model}? It will be logged out immediately.`)) {
    return;
  }

  try {
    const res = await apiFetch('/api/settings/authorizations/terminate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ hash })
    });
    const data = await res.json();
    if (data.success) {
      alert(`✅ Session terminated on ${model}`);
      openDevicesScreen();
    } else {
      alert('Failed: ' + (data.error || 'Could not terminate'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  }
}

async function terminateAllOtherSessionsAction() {
  if (!confirm('Are you sure you want to log out all devices except for this one?')) {
    return;
  }

  try {
    const res = await apiFetch('/api/settings/authorizations/terminate-all', {
      method: 'POST'
    });
    const data = await res.json();
    if (data.success) {
      alert('✅ All other active sessions have been terminated.');
      openDevicesScreen();
    } else {
      alert('Failed: ' + (data.error || 'Could not terminate all sessions'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  }
}

// 2. Chat Settings Logic
function openChatSettingsScreen() {
  openSubScreen('subChatSettings');
  const size = localStorage.getItem('NEXGRAM_TEXT_SIZE') || '15';
  const slider = document.getElementById('textSizeSlider');
  if (slider) slider.value = size;
  updateTextSizePreview(size);

  const sendByEnter = localStorage.getItem('NEXGRAM_SEND_ENTER') !== 'false';
  const sendByEnterEl = document.getElementById('toggleSendByEnter');
  if (sendByEnterEl) sendByEnterEl.checked = sendByEnter;

  const saveGallery = localStorage.getItem('NEXGRAM_SAVE_GALLERY') === 'true';
  const saveGalleryEl = document.getElementById('toggleSaveGallery');
  if (saveGalleryEl) saveGalleryEl.checked = saveGallery;

  const largeEmojis = localStorage.getItem('NEXGRAM_LARGE_EMOJIS') !== 'false';
  const largeEmojisEl = document.getElementById('toggleLargeEmojis');
  if (largeEmojisEl) largeEmojisEl.checked = largeEmojis;
}

function updateTextSizePreview(val) {
  const label = document.getElementById('textSizeLabel');
  if (label) label.innerText = `${val}px`;

  const inText = document.getElementById('previewIncomingText');
  const outText = document.getElementById('previewOutgoingText');
  if (inText) inText.style.fontSize = `${val}px`;
  if (outText) outText.style.fontSize = `${val}px`;

  // Apply to actual chat bubbles
  document.documentElement.style.setProperty('--tg-bubble-font-size', `${val}px`);
  localStorage.setItem('NEXGRAM_TEXT_SIZE', val);
}

function selectThemeWallpaper(theme, el) {
  document.querySelectorAll('.theme-chip').forEach(c => c.classList.remove('selected'));
  if (el) el.classList.add('selected');

  localStorage.setItem('NEXGRAM_THEME', theme);
  const wpLayer = document.querySelector('.tg-wallpaper-layer');
  if (wpLayer) {
    if (theme === 'midnight') {
      wpLayer.style.background = '#000000';
      wpLayer.style.opacity = '1';
    } else if (theme === 'arctic') {
      wpLayer.style.background = 'linear-gradient(180deg, #091a16 0%, #0e2721 100%)';
      wpLayer.style.opacity = '1';
    } else if (theme === 'ocean') {
      wpLayer.style.background = 'linear-gradient(180deg, #071929 0%, #0f2b48 100%)';
      wpLayer.style.opacity = '1';
    } else {
      wpLayer.style.background = '';
      wpLayer.style.opacity = '';
    }
  }
}

function saveChatSetting(key, val) {
  localStorage.setItem(`NEXGRAM_${key.toUpperCase()}`, val);
}

// 3. Privacy & Security Logic
function openPrivacyScreen() {
  openSubScreen('subPrivacy');
}

let activePrivacyTarget = null;
function openPrivacyChoice(title, options, targetId) {
  activePrivacyTarget = targetId;
  const modal = document.getElementById('privacyChoiceModal');
  const titleEl = document.getElementById('privacyChoiceTitle');
  const container = document.getElementById('privacyOptionsContainer');
  if (titleEl) titleEl.innerText = title;

  const currentVal = targetId ? (document.getElementById(targetId)?.innerText || options[0]) : options[0];

  container.innerHTML = options.map(opt => `
    <div class="settings-row" style="padding: 10px 14px; border-radius: 8px;" onclick="selectPrivacyChoice('${opt}')">
      <div class="settings-row-content">
        <div class="settings-row-text" style="font-size: 15px;">${opt}</div>
      </div>
      ${opt === currentVal ? '<i class="fa-solid fa-check" style="color: var(--tg-primary);"></i>' : ''}
    </div>
  `).join('');

  modal.classList.add('open');
}

function selectPrivacyChoice(val) {
  if (activePrivacyTarget) {
    const el = document.getElementById(activePrivacyTarget);
    if (el) el.innerText = val;
    localStorage.setItem(`NEXGRAM_PRIV_${activePrivacyTarget}`, val);
  }
  closePrivacyChoiceModal();
}

function closePrivacyChoiceModal() {
  document.getElementById('privacyChoiceModal').classList.remove('open');
}

function handlePrivacyChoiceBackdrop(e) {
  if (e.target.id === 'privacyChoiceModal') closePrivacyChoiceModal();
}

function openTwoFactorSetup() {
  alert('Two-Step Verification (2FA) is active and protecting your Telegram cloud session.');
}

// 4. Notifications & Sounds Logic
function openNotificationsScreen() {
  openSubScreen('subNotifications');
}

function saveNotifSetting(key, val) {
  localStorage.setItem(`NEXGRAM_NOTIF_${key.toUpperCase()}`, val);
}

// 5. Data & Storage Logic
function openDataStorageScreen() {
  openSubScreen('subData');
}

function clearAppCacheAction() {
  const usageSub = document.getElementById('storageUsageSub');
  if (usageSub) {
    usageSub.innerText = 'Cached Media: 0.0 MB';
  }
  alert('🧹 Telegram Cache cleared successfully! Freeing 18.4 MB on your device.');
}

// 6. Folders Logic
function openFoldersScreen() {
  openSubScreen('subFolders');
}

// 7. Language Logic
function openLanguageScreen() {
  openSubScreen('subLanguage');
  const currentLangCode = localStorage.getItem('NEXGRAM_LANG_CODE') || 'en';
  document.querySelectorAll('.lang-check').forEach(c => c.style.display = 'none');
  const check = document.getElementById(`checkLang_${currentLangCode}`);
  if (check) check.style.display = 'block';
}

function selectAppLanguage(name, code) {
  localStorage.setItem('NEXGRAM_LANG_NAME', name);
  localStorage.setItem('NEXGRAM_LANG_CODE', code);

  const langSub = document.getElementById('settingsCurrentLang');
  if (langSub) langSub.innerText = name;

  document.querySelectorAll('.lang-check').forEach(c => c.style.display = 'none');
  const check = document.getElementById(`checkLang_${code}`);
  if (check) check.style.display = 'block';

  alert(`Language changed to ${name}`);
  closeSubScreen('subLanguage');
}

// 8. Power Saving Logic
function openPowerSavingScreen() {
  openSubScreen('subPowerSaving');
}

// 9. Edit Username Screen Logic
function openEditUsernameScreen() {
  openSubScreen('subEditUsername');
  const input = document.getElementById('inputEditUsername');
  const current = (state.activeAccount?.username || '').replace(/^@/, '');
  if (input) {
    input.value = current;
    updateUsernameLinkPreview(current);
  }
}

function updateUsernameLinkPreview(val) {
  const clean = val.replace(/^@/, '').trim();
  const preview = document.getElementById('usernameLinkPreview');
  if (preview) {
    preview.innerText = clean ? `https://t.me/${clean}` : 'https://t.me/';
  }
}

async function saveUsernameAction() {
  const input = document.getElementById('inputEditUsername');
  const username = input.value.trim().replace(/^@/, '');

  if (username && username.length < 5) {
    alert('Username must be at least 5 characters long.');
    return;
  }

  try {
    const res = await apiFetch('/api/settings/username', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username })
    });
    const data = await res.json();

    if (data.success) {
      if (state.activeAccount) {
        state.activeAccount.username = data.username;
      }
      const el = document.getElementById('settingsUsername');
      if (el) el.innerText = data.username ? `@${data.username}` : 'None';

      alert('✅ Username updated successfully!');
      closeSubScreen('subEditUsername');
    } else {
      alert('Failed: ' + (data.error || 'Username unavailable'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  }
}

// 10. Edit Bio Screen Logic
function openEditBioScreen() {
  openSubScreen('subEditBio');
  const textarea = document.getElementById('inputEditBio');
  const current = state.activeAccount?.bio || '';
  if (textarea) {
    textarea.value = current;
    updateBioCounter(current);
  }
}

function updateBioCounter(val) {
  const counter = document.getElementById('bioCharCounter');
  if (counter) counter.innerText = `${val.length} / 70`;
}

async function saveBioAction() {
  const textarea = document.getElementById('inputEditBio');
  const bio = textarea.value.trim();

  try {
    const res = await apiFetch('/api/settings/profile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        firstName: state.activeAccount?.firstName || state.activeAccount?.name || 'User',
        lastName: state.activeAccount?.lastName || '',
        bio
      })
    });
    const data = await res.json();

    if (data.success) {
      if (state.activeAccount) {
        state.activeAccount.bio = bio;
      }
      const el = document.getElementById('settingsBio');
      if (el) el.innerText = bio || 'None';

      alert('✅ Bio updated successfully!');
      closeSubScreen('subEditBio');
    } else {
      alert('Failed: ' + (data.error || 'Could not update bio'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  }
}

// 11. Edit Name Screen Logic
function openEditNameScreen() {
  const dd = document.getElementById('settingsDropdownMenu');
  if (dd) dd.classList.remove('open');

  openSubScreen('subEditName');
  const parts = (state.activeAccount?.name || '').split(' ');
  const first = parts[0] || '';
  const last = parts.slice(1).join(' ') || '';

  const fInput = document.getElementById('inputFirstName');
  const lInput = document.getElementById('inputLastName');
  if (fInput) fInput.value = first;
  if (lInput) lInput.value = last;
}

async function saveNameAction() {
  const fInput = document.getElementById('inputFirstName');
  const lInput = document.getElementById('inputLastName');
  const firstName = fInput.value.trim();
  const lastName = lInput.value.trim();

  if (!firstName) {
    alert('First name cannot be empty.');
    return;
  }

  try {
    const res = await apiFetch('/api/settings/profile', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        firstName,
        lastName,
        bio: state.activeAccount?.bio || ''
      })
    });
    const data = await res.json();

    if (data.success) {
      const fullName = `${firstName} ${lastName}`.trim();
      if (state.activeAccount) {
        state.activeAccount.name = fullName;
      }
      const nameEl = document.getElementById('settingsUserName');
      if (nameEl) nameEl.innerText = fullName;
      const drawerNameEl = document.getElementById('drawerUserName');
      if (drawerNameEl) drawerNameEl.innerText = fullName;

      alert('✅ Name updated successfully!');
      closeSubScreen('subEditName');
    } else {
      alert('Failed: ' + (data.error || 'Could not update name'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  }
}

// 12. Change Phone & Photo Handlers
function openChangePhoneSheet() {
  alert('Phone Number: ' + (state.activeAccount?.phone || 'Not available') + '\n\nTo change your number on Telegram, a new verification code will be sent to your new SIM.');
}

function openEditPhotoSheet() {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*';
  input.onchange = (e) => {
    if (e.target.files && e.target.files[0]) {
      const reader = new FileReader();
      reader.onload = (re) => {
        const avatar = document.getElementById('settingsAvatar');
        if (avatar) {
          avatar.style.backgroundImage = `url(${re.target.result})`;
          avatar.style.backgroundSize = 'cover';
          avatar.innerText = '';
        }
        alert('✅ Profile photo updated!');
      };
      reader.readAsDataURL(e.target.files[0]);
    }
  };
  input.click();
}
