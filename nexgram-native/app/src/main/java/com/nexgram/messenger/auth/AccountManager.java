package com.nexgram.messenger.auth;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Native Multi-Account Manager for NexGram
 * Handles multiple authorized Telegram accounts locally on the Android device.
 */
public class AccountManager {
    private static final String PREFS_NAME = "nexgram_accounts_meta";
    private static final String KEY_ACCOUNTS_LIST = "accounts_list";
    private static final String KEY_ACTIVE_ACCOUNT_ID = "active_account_id";

    public static class AccountItem {
        public String id;
        public String name;
        public String phone;
        public String username;
        public long registeredAt;

        public AccountItem(String id, String name, String phone, String username) {
            this.id = id;
            this.name = name;
            this.phone = phone;
            this.username = username;
            this.registeredAt = System.currentTimeMillis();
        }
    }

    private final Context context;
    private final SecureSessionVault sessionVault;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final List<AccountItem> accounts = new ArrayList<>();
    private String activeAccountId = null;

    public AccountManager(Context context, SecureSessionVault sessionVault) {
        this.context = context;
        this.sessionVault = sessionVault;
        this.prefs = context != null ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;
        if (this.prefs != null) {
            loadAccounts();
        }
    }

    public AccountManager(SharedPreferences customPrefs, SecureSessionVault sessionVault) {
        this.context = null;
        this.sessionVault = sessionVault;
        this.prefs = customPrefs;
        if (this.prefs != null) {
            loadAccounts();
        }
    }

    private void loadAccounts() {
        String json = prefs.getString(KEY_ACCOUNTS_LIST, null);
        accounts.clear();
        if (json != null) {
            Type listType = new TypeToken<ArrayList<AccountItem>>() {}.getType();
            List<AccountItem> loaded = gson.fromJson(json, listType);
            if (loaded != null) {
                accounts.addAll(loaded);
            }
        }
        activeAccountId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, accounts.isEmpty() ? null : accounts.get(0).id);
    }

    private void saveAccounts() {
        prefs.edit()
                .putString(KEY_ACCOUNTS_LIST, gson.toJson(accounts))
                .putString(KEY_ACTIVE_ACCOUNT_ID, activeAccountId)
                .apply();
    }

    public synchronized void addAccount(AccountItem item, String sessionAuthKey) {
        if (sessionVault == null) {
            throw new SecurityException("SecureSessionVault is unavailable. Plaintext credential storage is disallowed.");
        }
        if (item == null || item.id == null || item.id.trim().isEmpty()) {
            throw new IllegalArgumentException("Account item and ID cannot be null or empty");
        }
        // Save to secure encrypted vault first before metadata
        sessionVault.saveSession(item.id, sessionAuthKey);

        // Prevent duplicate IDs in metadata
        accounts.removeIf(a -> a.id.equals(item.id));
        accounts.add(item);
        activeAccountId = item.id;
        saveAccounts();
    }

    public synchronized void switchAccount(String accountId) {
        if (accountId == null) return;
        for (AccountItem item : accounts) {
            if (item.id.equals(accountId)) {
                activeAccountId = accountId;
                saveAccounts();
                break;
            }
        }
    }

    public synchronized void removeAccount(String accountId) {
        if (accountId == null) return;
        accounts.removeIf(a -> a.id.equals(accountId));
        if (sessionVault != null) {
            sessionVault.removeSession(accountId);
        }
        if (activeAccountId != null && activeAccountId.equals(accountId)) {
            activeAccountId = accounts.isEmpty() ? null : accounts.get(0).id;
        }
        saveAccounts();
    }

    public AccountItem getActiveAccount() {
        if (activeAccountId == null) return null;
        for (AccountItem item : accounts) {
            if (item.id.equals(activeAccountId)) return item;
        }
        return null;
    }

    public List<AccountItem> getAllAccounts() {
        return new ArrayList<>(accounts);
    }

    public int getAccountCount() {
        return accounts.size();
    }
}
