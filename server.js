const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const { TelegramClient, Api } = require('telegram');
const { StringSession } = require('telegram/sessions');
const { SocksClient } = require('socks');
const net = require('net');
let config;
try {
  config = require('./config');
} catch (e) {
  config = {
    API_ID: process.env.API_ID ? parseInt(process.env.API_ID) : 0,
    API_HASH: process.env.API_HASH || '',
    PORT: process.env.PORT || 3000,
    APP_NAME: 'NexGram',
    VERSION: '13.2.2',
    DEVICE_MODEL: process.env.DEVICE_MODEL || '',
    SYSTEM_VERSION: process.env.SYSTEM_VERSION || ''
  };
}

function getDeviceModel(custom, req) {
  if (custom && typeof custom === 'string' && custom.trim()) {
    return custom.trim();
  }
  if (config.DEVICE_MODEL) {
    return config.DEVICE_MODEL;
  }
  const ua = req?.headers?.['user-agent'] || '';
  if (ua) {
    const m = ua.match(/\(Linux; Android [^;]+; ([^;)]+)\)/);
    if (m && m[1]) return m[1].replace(/Build\/.*/, '').trim();
    if (/iPhone/.test(ua)) return 'iPhone';
    if (/Windows/.test(ua)) return 'Windows PC';
    if (/Macintosh/.test(ua)) return 'Mac';
  }
  const os = require('os');
  return `${os.type()} ${os.arch()}`;
}

function getSystemVersion(custom, req) {
  if (custom && typeof custom === 'string' && custom.trim()) {
    return custom.trim();
  }
  if (config.SYSTEM_VERSION) {
    return config.SYSTEM_VERSION;
  }
  const ua = req?.headers?.['user-agent'] || '';
  const m = ua.match(/Android ([0-9.]+)/);
  if (m) return `Android ${m[1]}`;
  const os = require('os');
  return `${os.type()} ${os.release()}`;
}

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const ACCOUNTS_FILE = path.join(__dirname, 'accounts.json');

// Initialize accounts storage
if (!fs.existsSync(ACCOUNTS_FILE)) {
  fs.writeFileSync(ACCOUNTS_FILE, JSON.stringify({ activeAccountId: null, accounts: [] }, null, 2));
}

function getStoredData() {
  try {
    const raw = fs.readFileSync(ACCOUNTS_FILE, 'utf8');
    return JSON.parse(raw);
  } catch (err) {
    console.error('Error reading accounts file:', err);
    return { activeAccountId: null, accounts: [] };
  }
}

function saveStoredData(data) {
  try {
    fs.writeFileSync(ACCOUNTS_FILE, JSON.stringify(data, null, 2));
  } catch (err) {
    console.error('Error saving accounts file:', err);
  }
}

// Active TelegramClient instances in memory
// Map: accountId -> TelegramClient
const activeClients = new Map();

// Temporary auth sessions during login flow
// Map: sessionTempId -> { client, phoneNumber, phoneCodeHash, proxyConfig, createdAt }
const tempAuthSessions = new Map();

// Helper to cleanup old temp sessions (> 15 mins)
setInterval(() => {
  const now = Date.now();
  for (const [id, s] of tempAuthSessions.entries()) {
    if (now - s.createdAt > 15 * 60 * 1000) {
      try { s.client.disconnect(); } catch (e) {}
      tempAuthSessions.delete(id);
    }
  }
}, 60000);

// Build GramJS proxy option object
function buildProxyOption(proxyConfig) {
  if (!proxyConfig || !proxyConfig.enabled || !proxyConfig.host || !proxyConfig.port) {
    return undefined;
  }

  const port = parseInt(proxyConfig.port, 10);
  const type = proxyConfig.type ? proxyConfig.type.toUpperCase() : 'SOCKS5';

  if (type === 'MTPROXY') {
    return {
      MTProxy: true,
      ip: proxyConfig.host,
      port: port,
      secret: proxyConfig.secret || ''
    };
  }

  // SOCKS5 or SOCKS4
  return {
    ip: proxyConfig.host,
    port: port,
    socksType: type === 'SOCKS4' ? 4 : 5,
    username: proxyConfig.username || undefined,
    password: proxyConfig.password || undefined,
    timeout: 10
  };
}

// Helper to purge terminated/invalid account automatically
function purgeAccount(accountId) {
  console.log(`[Auto-Purge] Account ${accountId} was terminated on Telegram. Removing...`);
  const data = getStoredData();
  data.accounts = data.accounts.filter(a => a.id !== accountId);
  if (data.activeAccountId === accountId) {
    data.activeAccountId = data.accounts.length > 0 ? data.accounts[0].id : null;
  }
  saveStoredData(data);

  if (activeClients.has(accountId)) {
    try { activeClients.get(accountId).disconnect(); } catch (e) {}
    activeClients.delete(accountId);
  }

  return data.activeAccountId;
}

function isSessionRevokedError(err) {
  if (!err || !err.message) return false;
  const msg = err.message.toUpperCase();
  return msg.includes('AUTH_KEY_UNREGISTERED') ||
         msg.includes('SESSION_REVOKED') ||
         msg.includes('SESSION_EXPIRED') ||
         msg.includes('USER_DEACTIVATED') ||
         msg.includes('401');
}

// Create & connect client for an account
async function getOrInitClient(account) {
  if (activeClients.has(account.id)) {
    const client = activeClients.get(account.id);
    if (client.connected) return client;
    try {
      await client.connect();
      return client;
    } catch (e) {
      if (isSessionRevokedError(e)) {
        purgeAccount(account.id);
        throw e;
      }
      console.warn(`Reconnecting client ${account.id} failed, recreating...`, e.message);
      activeClients.delete(account.id);
    }
  }

  const stringSession = new StringSession(account.sessionString || '');
  const proxyOption = buildProxyOption(account.proxy);

  const client = new TelegramClient(stringSession, config.API_ID, config.API_HASH, {
    deviceModel: getDeviceModel(account.deviceModel),
    systemVersion: getSystemVersion(account.systemVersion),
    appVersion: config.VERSION || '13.2.2',
    langCode: 'en',
    systemLangCode: 'en',
    connectionRetries: 3,
    proxy: proxyOption,
    autoReconnect: true
  });

  try {
    await client.connect();
    activeClients.set(account.id, client);
    return client;
  } catch (err) {
    if (isSessionRevokedError(err)) {
      purgeAccount(account.id);
    }
    throw err;
  }
}

// Helper to get active client
async function getActiveClient() {
  const data = getStoredData();
  if (!data.activeAccountId) return null;
  const account = data.accounts.find(a => a.id === data.activeAccountId);
  if (!account) return null;
  
  try {
    return await getOrInitClient(account);
  } catch (err) {
    if (isSessionRevokedError(err)) {
      const nextAccId = purgeAccount(account.id);
      if (nextAccId) {
        const nextAcc = getStoredData().accounts.find(a => a.id === nextAccId);
        if (nextAcc) return await getOrInitClient(nextAcc);
      }
    }
    throw err;
  }
}

// ----------------- API ROUTES -----------------

// 1. Get system & active account status
app.get('/api/status', async (req, res) => {
  let data = getStoredData();
  let activeAccount = data.accounts.find(a => a.id === data.activeAccountId) || null;

  let isConnected = false;
  let me = null;

  if (activeAccount) {
    try {
      const client = await getOrInitClient(activeAccount);
      isConnected = client.connected;
      if (isConnected) {
        me = await client.getMe();
      }
    } catch (err) {
      console.error('Status check error:', err.message);
      if (isSessionRevokedError(err)) {
        purgeAccount(activeAccount.id);
        data = getStoredData();
        activeAccount = data.accounts.find(a => a.id === data.activeAccountId) || null;
        if (activeAccount) {
          try {
            const nextClient = await getOrInitClient(activeAccount);
            isConnected = nextClient.connected;
            if (isConnected) me = await nextClient.getMe();
          } catch (e2) {}
        }
      }
      isConnected = false;
    }
  }

  res.json({
    appName: config.APP_NAME,
    apiId: config.API_ID,
    accountsCount: data.accounts.length,
    activeAccountId: data.activeAccountId,
    activeAccount: activeAccount ? {
      id: activeAccount.id,
      name: activeAccount.name,
      phone: activeAccount.phone,
      username: activeAccount.username,
      proxy: activeAccount.proxy,
      sessionString: activeAccount.sessionString,
      isConnected
    } : null,
    me: me ? {
      id: me.id ? me.id.toString() : null,
      firstName: me.firstName,
      lastName: me.lastName,
      username: me.username,
      phone: me.phone
    } : null
  });
});

// 2. List all accounts
app.get('/api/accounts', (req, res) => {
  const data = getStoredData();
  const list = data.accounts.map(acc => ({
    id: acc.id,
    name: acc.name,
    phone: acc.phone,
    username: acc.username,
    proxy: acc.proxy,
    sessionString: acc.sessionString,
    isActive: acc.id === data.activeAccountId,
    addedAt: acc.addedAt
  }));
  res.json({ accounts: list, activeAccountId: data.activeAccountId });
});

// 3. Switch active account
app.post('/api/accounts/switch', (req, res) => {
  const { accountId } = req.body;
  const data = getStoredData();
  const exists = data.accounts.find(a => a.id === accountId);
  if (!exists) {
    return res.status(404).json({ error: 'Account not found' });
  }

  data.activeAccountId = accountId;
  saveStoredData(data);
  res.json({ success: true, activeAccountId: accountId });
});

// 4. Delete account
app.delete('/api/accounts/:id', async (req, res) => {
  const accountId = req.params.id;
  const data = getStoredData();

  if (activeClients.has(accountId)) {
    try {
      const client = activeClients.get(accountId);
      await client.disconnect();
    } catch (e) {}
    activeClients.delete(accountId);
  }

  data.accounts = data.accounts.filter(a => a.id !== accountId);
  if (data.activeAccountId === accountId) {
    data.activeAccountId = data.accounts.length > 0 ? data.accounts[0].id : null;
  }
  saveStoredData(data);
  res.json({ success: true });
});

// 4b. Logout current active account
app.post('/api/auth/logout', async (req, res) => {
  const data = getStoredData();
  const currentId = data.activeAccountId;
  if (!currentId) {
    return res.json({ success: true, remainingAccounts: 0 });
  }

  if (activeClients.has(currentId)) {
    try {
      const client = activeClients.get(currentId);
      await client.disconnect();
    } catch (e) {}
    activeClients.delete(currentId);
  }

  data.accounts = data.accounts.filter(a => a.id !== currentId);
  data.activeAccountId = data.accounts.length > 0 ? data.accounts[0].id : null;
  saveStoredData(data);

  res.json({
    success: true,
    activeAccountId: data.activeAccountId,
    remainingAccounts: data.accounts.length
  });
});

// 4c. Settings: Get Full User Profile (Phone, Username, Bio)
app.get('/api/settings/profile', async (req, res) => {
  try {
    const client = await getActiveClient();
    const me = await client.getMe();
    let about = '';
    try {
      const full = await client.invoke(new Api.users.GetFullUser({ id: 'me' }));
      about = full.fullUser?.about || '';
    } catch (e) {}

    res.json({
      success: true,
      id: me.id ? me.id.toString() : '',
      firstName: me.firstName || '',
      lastName: me.lastName || '',
      name: `${me.firstName || ''} ${me.lastName || ''}`.trim() || me.username || me.phone,
      username: me.username || '',
      phone: me.phone || '',
      bio: about
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 4d. Settings: Update User Name & Bio
app.post('/api/settings/profile', async (req, res) => {
  const { firstName, lastName, bio } = req.body;
  try {
    const client = await getActiveClient();
    await client.invoke(new Api.account.UpdateProfile({
      firstName: firstName || '',
      lastName: lastName || '',
      about: bio !== undefined ? bio : ''
    }));

    const data = getStoredData();
    const acc = data.accounts.find(a => a.id === data.activeAccountId);
    if (acc) {
      acc.name = `${firstName || ''} ${lastName || ''}`.trim() || acc.name;
      acc.bio = bio;
      saveStoredData(data);
    }

    res.json({ success: true, message: 'Profile updated successfully' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 4e. Settings: Update Username
app.post('/api/settings/username', async (req, res) => {
  const { username } = req.body;
  try {
    const client = await getActiveClient();
    const cleanUsername = (username || '').replace(/^@/, '').trim();
    await client.invoke(new Api.account.UpdateUsername({
      username: cleanUsername
    }));

    const data = getStoredData();
    const acc = data.accounts.find(a => a.id === data.activeAccountId);
    if (acc) {
      acc.username = cleanUsername;
      saveStoredData(data);
    }

    res.json({ success: true, username: cleanUsername });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 4f. Settings: Get Authorizations / Active Sessions (Real Telegram API)
app.get('/api/settings/authorizations', async (req, res) => {
  try {
    const client = await getActiveClient();
    const result = await client.invoke(new Api.account.GetAuthorizations());
    const sessions = (result.authorizations || []).map(a => ({
      hash: a.hash ? a.hash.toString() : '',
      deviceModel: a.deviceModel || 'Unknown Device',
      platform: a.platform || '',
      systemVersion: a.systemVersion || '',
      appName: a.appName || '',
      appVersion: a.appVersion || '',
      dateCreated: a.dateCreated,
      dateActive: a.dateActive,
      ip: a.ip || '',
      country: a.country || 'Unknown Location',
      region: a.region || '',
      current: a.flags ? (a.flags & 1) !== 0 : false
    }));

    res.json({ success: true, authorizations: sessions });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 4g. Settings: Terminate a specific session
app.post('/api/settings/authorizations/terminate', async (req, res) => {
  const { hash } = req.body;
  if (!hash) return res.status(400).json({ error: 'Hash is required' });
  try {
    const client = await getActiveClient();
    await client.invoke(new Api.account.ResetAuthorization({
      hash: BigInt(hash)
    }));
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 4h. Settings: Terminate All Other Sessions
app.post('/api/settings/authorizations/terminate-all', async (req, res) => {
  try {
    const client = await getActiveClient();
    await client.invoke(new Api.account.ResetAuthorizations());
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 5. Update Proxy for an account
app.post('/api/accounts/proxy', async (req, res) => {
  const { accountId, proxy } = req.body;
  const data = getStoredData();
  const acc = data.accounts.find(a => a.id === accountId);
  if (!acc) {
    return res.status(404).json({ error: 'Account not found' });
  }

  acc.proxy = proxy;
  saveStoredData(data);

  // Disconnect existing client so it recreates with the new proxy
  if (activeClients.has(accountId)) {
    try {
      const client = activeClients.get(accountId);
      await client.disconnect();
    } catch (e) {}
    activeClients.delete(accountId);
  }

  res.json({ success: true, proxy: acc.proxy });
});

// 6. Test Proxy latency and connection
app.post('/api/proxy/test', async (req, res) => {
  const { host, port, type, username, password } = req.body;
  if (!host || !port) {
    return res.status(400).json({ error: 'Host and port are required' });
  }

  const startTime = Date.now();
  const proxyPort = parseInt(port, 10);
  const proxyType = (type || 'SOCKS5').toUpperCase();

  try {
    if (proxyType === 'SOCKS5' || proxyType === 'SOCKS4') {
      const options = {
        proxy: {
          host: host,
          port: proxyPort,
          type: proxyType === 'SOCKS4' ? 4 : 5,
          userId: username || undefined,
          password: password || undefined
        },
        command: 'connect',
        destination: {
          host: '149.154.167.50', // Telegram DC2 IP
          port: 443
        },
        timeout: 7000
      };

      const info = await SocksClient.createConnection(options);
      const latency = Date.now() - startTime;
      info.socket.destroy();
      return res.json({ success: true, latency, message: `Connected to Telegram DC via ${proxyType} in ${latency}ms` });
    } else {
      // Direct TCP ping
      const socket = new net.Socket();
      socket.setTimeout(5000);

      socket.connect(proxyPort, host, () => {
        const latency = Date.now() - startTime;
        socket.destroy();
        return res.json({ success: true, latency, message: `Proxy server reachable in ${latency}ms` });
      });

      socket.on('error', (err) => {
        socket.destroy();
        return res.status(500).json({ success: false, error: err.message });
      });

      socket.on('timeout', () => {
        socket.destroy();
        return res.status(504).json({ success: false, error: 'Connection timed out (5s)' });
      });
    }
  } catch (err) {
    return res.status(500).json({ success: false, error: err.message });
  }
});

// 7. Auth: Step 1 - Send Code
app.post('/api/auth/send-code', async (req, res) => {
  const { phoneNumber, proxy } = req.body;
  if (!phoneNumber) {
    return res.status(400).json({ error: 'Phone number is required' });
  }

  const cleanPhone = phoneNumber.replace(/[\s\-\(\)]/g, '');
  const proxyOption = buildProxyOption(proxy);

  const stringSession = new StringSession('');
  const client = new TelegramClient(stringSession, config.API_ID, config.API_HASH, {
    deviceModel: getDeviceModel(req.body.deviceModel, req),
    systemVersion: getSystemVersion(req.body.systemVersion, req),
    appVersion: config.VERSION || '13.2.2',
    langCode: 'en',
    systemLangCode: 'en',
    connectionRetries: 3,
    proxy: proxyOption
  });

  try {
    await client.connect();
    const result = await client.sendCode({
      apiId: config.API_ID,
      apiHash: config.API_HASH
    }, cleanPhone);

    const sessionTempId = 'temp_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
    tempAuthSessions.set(sessionTempId, {
      client,
      phoneNumber: cleanPhone,
      phoneCodeHash: result.phoneCodeHash,
      proxyConfig: proxy,
      createdAt: Date.now()
    });

    res.json({
      success: true,
      sessionTempId,
      phoneCodeHash: result.phoneCodeHash,
      isCodeViaApp: result.isCodeViaApp || false,
      message: 'Verification code sent to Telegram / SMS'
    });
  } catch (err) {
    console.error('SendCode Error:', err);
    try { client.disconnect(); } catch (e) {}
    res.status(500).json({ error: err.message || 'Failed to send code' });
  }
});

// 8. Auth: Step 2 - Sign In with OTP
app.post('/api/auth/sign-in', async (req, res) => {
  const { sessionTempId, phoneCode } = req.body;
  if (!sessionTempId || !phoneCode) {
    return res.status(400).json({ error: 'sessionTempId and phoneCode are required' });
  }

  const tempSession = tempAuthSessions.get(sessionTempId);
  if (!tempSession) {
    return res.status(404).json({ error: 'Login session expired or invalid. Please start again.' });
  }

  const { client, phoneNumber, phoneCodeHash, proxyConfig } = tempSession;

  try {
    await client.invoke(new Api.auth.SignIn({
      phoneNumber: phoneNumber,
      phoneCodeHash: phoneCodeHash,
      phoneCode: phoneCode.trim()
    }));

    // Successful login!
    const me = await client.getMe();
    const sessionString = client.session.save();

    const newAccount = {
      id: 'acc_' + (me.id ? me.id.toString() : Date.now()),
      name: `${me.firstName || ''} ${me.lastName || ''}`.trim() || me.username || phoneNumber,
      username: me.username || '',
      phone: phoneNumber,
      proxy: proxyConfig || { enabled: false },
      sessionString: sessionString,
      addedAt: new Date().toISOString()
    };

    const data = getStoredData();
    // Check if account already exists
    const idx = data.accounts.findIndex(a => a.phone === phoneNumber || (me.id && a.id === newAccount.id));
    if (idx >= 0) {
      data.accounts[idx] = newAccount;
    } else {
      data.accounts.push(newAccount);
    }
    data.activeAccountId = newAccount.id;
    saveStoredData(data);

    activeClients.set(newAccount.id, client);
    tempAuthSessions.delete(sessionTempId);

    res.json({
      success: true,
      account: newAccount,
      stringSession: sessionString,
      message: 'Account logged in successfully!'
    });
  } catch (err) {
    console.error('SignIn Error:', err);
    if (err.errorMessage === 'SESSION_PASSWORD_NEEDED') {
      return res.json({
        success: false,
        needs2FA: true,
        sessionTempId,
        message: 'Two-Step Verification (2FA) password required'
      });
    }
    res.status(500).json({ error: err.message || 'Invalid code or login failed' });
  }
});

// 9. Auth: Step 3 - 2FA Password
app.post('/api/auth/2fa', async (req, res) => {
  const { sessionTempId, password } = req.body;
  if (!sessionTempId || !password) {
    return res.status(400).json({ error: 'sessionTempId and password are required' });
  }

  const tempSession = tempAuthSessions.get(sessionTempId);
  if (!tempSession) {
    return res.status(404).json({ error: 'Login session expired or invalid' });
  }

  const { client, phoneNumber, proxyConfig } = tempSession;

  try {
    await client.signInWithPassword({
      apiId: config.API_ID,
      apiHash: config.API_HASH
    }, {
      password: async () => password
    });

    const me = await client.getMe();
    const sessionString = client.session.save();

    const newAccount = {
      id: 'acc_' + (me.id ? me.id.toString() : Date.now()),
      name: `${me.firstName || ''} ${me.lastName || ''}`.trim() || me.username || phoneNumber,
      username: me.username || '',
      phone: phoneNumber,
      proxy: proxyConfig || { enabled: false },
      sessionString: sessionString,
      addedAt: new Date().toISOString()
    };

    const data = getStoredData();
    const idx = data.accounts.findIndex(a => a.phone === phoneNumber || (me.id && a.id === newAccount.id));
    if (idx >= 0) {
      data.accounts[idx] = newAccount;
    } else {
      data.accounts.push(newAccount);
    }
    data.activeAccountId = newAccount.id;
    saveStoredData(data);

    activeClients.set(newAccount.id, client);
    tempAuthSessions.delete(sessionTempId);

    res.json({
      success: true,
      account: newAccount,
      stringSession: sessionString,
      message: '2FA verified and account saved!'
    });
  } catch (err) {
    console.error('2FA Error:', err);
    res.status(500).json({ error: err.message || 'Incorrect 2FA password' });
  }
});

// 10. Auth: Import existing StringSession (for users who already have sessions)
app.post('/api/auth/import-session', async (req, res) => {
  const { sessionString, proxy, label } = req.body;
  if (!sessionString) {
    return res.status(400).json({ error: 'sessionString is required' });
  }

  const proxyOption = buildProxyOption(proxy);
  const stringSession = new StringSession(sessionString.trim());
  const client = new TelegramClient(stringSession, config.API_ID, config.API_HASH, {
    deviceModel: getDeviceModel(req.body.deviceModel, req),
    systemVersion: getSystemVersion(req.body.systemVersion, req),
    appVersion: config.VERSION || '13.2.2',
    langCode: 'en',
    systemLangCode: 'en',
    connectionRetries: 3,
    proxy: proxyOption
  });

  try {
    await client.connect();
    const me = await client.getMe();
    if (!me) {
      throw new Error('Could not retrieve user info with provided session');
    }

    const newAccount = {
      id: 'acc_' + (me.id ? me.id.toString() : Date.now()),
      name: label || `${me.firstName || ''} ${me.lastName || ''}`.trim() || me.username || me.phone,
      username: me.username || '',
      phone: me.phone || '',
      proxy: proxy || { enabled: false },
      sessionString: sessionString.trim(),
      addedAt: new Date().toISOString()
    };

    const data = getStoredData();
    const idx = data.accounts.findIndex(a => a.id === newAccount.id);
    if (idx >= 0) {
      data.accounts[idx] = newAccount;
    } else {
      data.accounts.push(newAccount);
    }
    data.activeAccountId = newAccount.id;
    saveStoredData(data);

    activeClients.set(newAccount.id, client);

    res.json({
      success: true,
      account: newAccount,
      message: 'Session imported successfully!'
    });
  } catch (err) {
    console.error('Import Session Error:', err);
    try { client.disconnect(); } catch (e) {}
    res.status(500).json({ error: err.message || 'Failed to authenticate with session' });
  }
});

// 10b. Auth: Start QR Code Login
app.post('/api/auth/qr-start', async (req, res) => {
  const { proxy } = req.body;
  const proxyOption = buildProxyOption(proxy);
  const stringSession = new StringSession('');
  const client = new TelegramClient(stringSession, config.API_ID, config.API_HASH, {
    deviceModel: getDeviceModel(req.body.deviceModel, req),
    systemVersion: getSystemVersion(req.body.systemVersion, req),
    appVersion: config.VERSION || '13.2.2',
    langCode: 'en',
    systemLangCode: 'en',
    connectionRetries: 3,
    proxy: proxyOption
  });

  try {
    await client.connect();
    const tokenResult = await client.invoke(new Api.auth.ExportLoginToken({
      apiId: config.API_ID,
      apiHash: config.API_HASH,
      exceptIds: []
    }));

    if (!(tokenResult instanceof Api.auth.LoginToken)) {
      throw new Error('Could not generate login token');
    }

    const tokenBase64 = Buffer.from(tokenResult.token).toString('base64url');
    const qrUrl = `tg://login?token=${tokenBase64}`;
    const sessionTempId = 'qr_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);

    tempAuthSessions.set(sessionTempId, {
      client,
      proxyConfig: proxy,
      createdAt: Date.now()
    });

    res.json({
      success: true,
      sessionTempId,
      qrUrl,
      expires: tokenResult.expires
    });
  } catch (err) {
    console.error('QR Start Error:', err);
    try { client.disconnect(); } catch (e) {}
    res.status(500).json({ error: err.message || 'Failed to start QR login' });
  }
});

// 10c. Auth: Check QR Code Login Status
app.post('/api/auth/qr-check', async (req, res) => {
  const { sessionTempId } = req.body;
  const tempSession = tempAuthSessions.get(sessionTempId);
  if (!tempSession) {
    return res.status(404).json({ error: 'QR session expired or invalid' });
  }

  const { client, proxyConfig } = tempSession;

  try {
    const result = await client.invoke(new Api.auth.ExportLoginToken({
      apiId: config.API_ID,
      apiHash: config.API_HASH,
      exceptIds: []
    }));

    if (result instanceof Api.auth.LoginTokenSuccess) {
      // User scanned and authorized!
      const me = await client.getMe();
      const sessionString = client.session.save();

      const newAccount = {
        id: 'acc_' + (me.id ? me.id.toString() : Date.now()),
        name: `${me.firstName || ''} ${me.lastName || ''}`.trim() || me.username || me.phone || 'User',
        username: me.username || '',
        phone: me.phone || '',
        proxy: proxyConfig || { enabled: false },
        sessionString: sessionString,
        addedAt: new Date().toISOString()
      };

      const data = getStoredData();
      const idx = data.accounts.findIndex(a => a.id === newAccount.id);
      if (idx >= 0) {
        data.accounts[idx] = newAccount;
      } else {
        data.accounts.push(newAccount);
      }
      data.activeAccountId = newAccount.id;
      saveStoredData(data);

      activeClients.set(newAccount.id, client);
      tempAuthSessions.delete(sessionTempId);

      return res.json({
        status: 'success',
        account: newAccount
      });
    } else if (result instanceof Api.auth.LoginTokenMigrateTo) {
      // Handle DC migration
      await client._switchDC(result.dcId);
      const migrated = await client.invoke(new Api.auth.ImportLoginToken({ token: result.token }));
      if (migrated instanceof Api.auth.LoginTokenSuccess) {
        const me = await client.getMe();
        const sessionString = client.session.save();

        const newAccount = {
          id: 'acc_' + (me.id ? me.id.toString() : Date.now()),
          name: `${me.firstName || ''} ${me.lastName || ''}`.trim() || me.username || me.phone || 'User',
          username: me.username || '',
          phone: me.phone || '',
          proxy: proxyConfig || { enabled: false },
          sessionString: sessionString,
          addedAt: new Date().toISOString()
        };

        const data = getStoredData();
        const idx = data.accounts.findIndex(a => a.id === newAccount.id);
        if (idx >= 0) {
          data.accounts[idx] = newAccount;
        } else {
          data.accounts.push(newAccount);
        }
        data.activeAccountId = newAccount.id;
        saveStoredData(data);

        activeClients.set(newAccount.id, client);
        tempAuthSessions.delete(sessionTempId);

        return res.json({
          status: 'success',
          account: newAccount
        });
      }
    }

    // Still waiting for user to scan
    res.json({ status: 'pending' });
  } catch (err) {
    if (err.errorMessage === 'SESSION_PASSWORD_NEEDED') {
      return res.json({ status: 'needs_2fa', sessionTempId });
    }
    console.error('QR Check Error:', err);
    res.json({ status: 'pending' });
  }
});

// 11. Telegram Dialogs (Chats List)
app.get('/api/dialogs', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account. Please login first.' });
    }

    const dialogs = await client.getDialogs({ limit: 40 });
    const formatted = dialogs.map(d => {
      let type = 'user';
      if (d.isGroup) type = 'group';
      else if (d.isChannel) type = 'channel';

      return {
        id: d.id ? d.id.toString() : null,
        title: d.title || (d.entity && (d.entity.firstName || d.entity.username)) || 'Unknown Chat',
        username: d.entity && d.entity.username ? d.entity.username : null,
        type: type,
        isGroup: d.isGroup,
        isChannel: d.isChannel,
        isUser: d.isUser,
        unreadCount: d.unreadCount || 0,
        date: d.date ? new Date(d.date * 1000).toISOString() : null,
        lastMessage: d.message && d.message.message ? d.message.message : (d.message ? '[Media/Action]' : '')
      };
    });

    res.json({ success: true, dialogs: formatted });
  } catch (err) {
    console.error('Get Dialogs Error:', err);
    if (isSessionRevokedError(err)) {
      const data = getStoredData();
      if (data.activeAccountId) {
        purgeAccount(data.activeAccountId);
      }
      return res.json({
        success: false,
        sessionRevoked: true,
        message: 'This session was terminated on Telegram and has been removed.'
      });
    }
    res.status(500).json({ error: err.message || 'Failed to fetch chats' });
  }
});

// 12. Telegram Messages in a Chat
app.get('/api/messages/:chatId', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const { chatId } = req.params;
    const limit = parseInt(req.query.limit, 10) || 50;

    let peer = chatId;
    if (/^\-?\d+$/.test(chatId)) {
      peer = parseInt(chatId, 10);
    }

    const messages = await client.getMessages(peer, { limit });
    const formatted = messages.map(m => {
      let sender = 'User';
      if (m.sender) {
        sender = m.sender.firstName ? `${m.sender.firstName} ${m.sender.lastName || ''}`.trim() : (m.sender.title || m.sender.username || 'User');
      }

      return {
        id: m.id,
        text: m.message || (m.media ? '[Media File]' : ''),
        date: m.date ? new Date(m.date * 1000).toISOString() : null,
        out: m.out,
        sender: sender,
        mediaType: m.media ? m.media.className : null
      };
    });

    // Return chronological order (oldest to newest)
    formatted.reverse();
    res.json({ success: true, messages: formatted });
  } catch (err) {
    console.error('Get Messages Error:', err);
    res.status(500).json({ error: err.message || 'Failed to fetch messages' });
  }
});

// 13. Send Message
app.post('/api/messages/send', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const { chatId, message } = req.body;
    if (!chatId || !message) {
      return res.status(400).json({ error: 'chatId and message are required' });
    }

    let peer = chatId;
    if (/^\-?\d+$/.test(chatId)) {
      peer = parseInt(chatId, 10);
    }

    const sent = await client.sendMessage(peer, { message });
    res.json({
      success: true,
      messageId: sent.id,
      text: sent.message,
      date: new Date(sent.date * 1000).toISOString()
    });
  } catch (err) {
    console.error('Send Message Error:', err);
    res.status(500).json({ error: err.message || 'Failed to send message' });
  }
});

// 14. Global Search (Chats, channels, users)
app.get('/api/search', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const query = req.query.q || '';
    if (!query) {
      return res.json({ success: true, results: [] });
    }

    const searchResult = await client.invoke(new Api.contacts.Search({
      q: query,
      limit: 20
    }));

    const results = [];
    if (searchResult.chats) {
      for (const chat of searchResult.chats) {
        results.push({
          id: chat.id ? chat.id.toString() : null,
          title: chat.title || 'Group / Channel',
          username: chat.username || null,
          type: chat.broadcast ? 'channel' : 'group',
          participantsCount: chat.participantsCount || 0
        });
      }
    }
    if (searchResult.users) {
      for (const user of searchResult.users) {
        results.push({
          id: user.id ? user.id.toString() : null,
          title: `${user.firstName || ''} ${user.lastName || ''}`.trim() || user.username || 'User',
          username: user.username || null,
          type: 'user'
        });
      }
    }

    res.json({ success: true, results });
  } catch (err) {
    console.error('Search Error:', err);
    res.status(500).json({ error: err.message || 'Search failed' });
  }
});

// 15. Get Chat / User Full Profile Info
app.get('/api/chats/:chatId/info', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const { chatId } = req.params;
    let peer = chatId;
    if (/^\-?\d+$/.test(chatId)) {
      peer = parseInt(chatId, 10);
    }

    const entity = await client.getEntity(peer);
    let title = entity.title || `${entity.firstName || ''} ${entity.lastName || ''}`.trim() || entity.username || 'Telegram User';
    let username = entity.username || '';
    let phone = entity.phone || '';
    let about = '';
    let membersCount = 0;
    let isChannel = false;
    let isGroup = false;
    let isUser = false;

    if (entity.className === 'User') {
      isUser = true;
      try {
        const full = await client.invoke(new Api.users.GetFullUser({ id: entity }));
        about = full.about || '';
      } catch (e) {}
    } else if (entity.className === 'Channel') {
      isChannel = entity.broadcast;
      isGroup = !entity.broadcast;
      try {
        const full = await client.invoke(new Api.channels.GetFullChannel({ channel: entity }));
        about = (full.fullChat && full.fullChat.about) || '';
        membersCount = (full.fullChat && full.fullChat.participantsCount) || 0;
      } catch (e) {}
    } else if (entity.className === 'Chat') {
      isGroup = true;
      membersCount = entity.participantsCount || 0;
    }

    res.json({
      success: true,
      info: {
        id: entity.id ? entity.id.toString() : chatId,
        title,
        username,
        phone,
        about,
        membersCount,
        isChannel,
        isGroup,
        isUser
      }
    });
  } catch (err) {
    console.error('Get Chat Info Error:', err);
    res.status(500).json({ error: err.message || 'Failed to get chat info' });
  }
});

// 16. Leave Channel or Group
app.post('/api/chats/:chatId/leave', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const { chatId } = req.params;
    let peer = chatId;
    if (/^\-?\d+$/.test(chatId)) {
      peer = parseInt(chatId, 10);
    }

    const entity = await client.getEntity(peer);
    if (entity.className === 'Channel') {
      await client.invoke(new Api.channels.LeaveChannel({
        channel: entity
      }));
    } else if (entity.className === 'Chat') {
      await client.invoke(new Api.messages.DeleteChatUser({
        chatId: entity.id,
        userId: new Api.InputUserSelf()
      }));
    } else {
      return res.status(400).json({ error: 'Cannot leave private chat' });
    }

    res.json({ success: true, message: 'Left successfully' });
  } catch (err) {
    console.error('Leave Chat Error:', err);
    res.status(500).json({ error: err.message || 'Failed to leave group/channel' });
  }
});

// 17. Mute or Unmute Chat
app.post('/api/chats/:chatId/mute', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const { chatId } = req.params;
    const { mute } = req.body; // true = mute, false = unmute

    let peer = chatId;
    if (/^\-?\d+$/.test(chatId)) {
      peer = parseInt(chatId, 10);
    }

    const inputPeer = await client.getInputEntity(peer);
    await client.invoke(new Api.account.UpdateNotifySettings({
      peer: new Api.InputNotifyPeer({ peer: inputPeer }),
      settings: new Api.InputPeerNotifySettings({
        muteUntil: mute ? 2147483647 : 0
      })
    }));

    res.json({ success: true, muted: !!mute });
  } catch (err) {
    console.error('Mute Error:', err);
    res.status(500).json({ error: err.message || 'Failed to update notification settings' });
  }
});

// 18. Delete Chat / Clear History
app.post('/api/chats/:chatId/delete', async (req, res) => {
  try {
    const client = await getActiveClient();
    if (!client) {
      return res.status(401).json({ error: 'No active account' });
    }

    const { chatId } = req.params;
    let peer = chatId;
    if (/^\-?\d+$/.test(chatId)) {
      peer = parseInt(chatId, 10);
    }

    const inputPeer = await client.getInputEntity(peer);
    await client.invoke(new Api.messages.DeleteHistory({
      peer: inputPeer,
      maxId: 0,
      justClear: false,
      revoke: true
    }));

    res.json({ success: true });
  } catch (err) {
    console.error('Delete History Error:', err);
    res.status(500).json({ error: err.message || 'Failed to delete chat' });
  }
});

// Start Express Server
const PORT = config.PORT;
app.listen(PORT, () => {
  console.log(`===============================================`);
  console.log(`🚀 ${config.APP_NAME} Telegram Client running at:`);
  console.log(`   http://localhost:${PORT}`);
  console.log(`===============================================`);
});
