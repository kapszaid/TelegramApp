package com.nexgram.messenger.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.adapter.ChatAdapter;
import com.nexgram.messenger.ui.adapter.ContactAdapter;
import com.nexgram.messenger.ui.adapter.DrawerAccountAdapter;
import com.nexgram.messenger.ui.helpers.ProxyDialogHelper;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modern Telegram Android Main Interface (Milestone 3.3 Redesign):
 * - 4-Tab Bottom Navigation: Chats, Contacts, Settings, Profile
 * - Top 3-dot overflow menu: New Group, New Channel, Saved Messages, Proxy Settings, Night Mode
 * - Modern Chat Folder Tabs
 * - Integrated Multi-Account Management inside the Profile tab
 * - Zero fake mock data: backed directly by TDLib 1.8.70 Native MTProto engine
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private CoordinatorLayout mainCoordinatorLayout;
    private Toolbar toolbar;
    private LinearLayout layoutSearchBar;
    private EditText etSearchQuery;
    private ImageView btnCloseSearch;
    private TabLayout tabLayoutFolders;
    private BottomNavigationView bottomNav;
    private FloatingActionButton fabNewChat;

    // Tab Containers
    private FrameLayout tabContainerChats;
    private FrameLayout tabContainerContacts;
    private NestedScrollView tabContainerSettings;
    private NestedScrollView tabContainerProfile;

    // Tab 0: Chats Views
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerViewChats;
    private ProgressBar progressBarChats;
    private View layoutEmptyState;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private Button btnEmptyStateLogin;

    // Tab 1: Contacts Views
    private SwipeRefreshLayout swipeRefreshContactsMain;
    private RecyclerView rvContactsMain;
    private ProgressBar pbContactsMain;
    private View layoutEmptyContactsMain;
    private ContactAdapter contactAdapter;
    private final List<TdApi.User> contactsList = new ArrayList<>();

    // Tab 2: Settings Views
    private LinearLayout mainRowNotifications;
    private LinearLayout mainRowPrivacy;
    private LinearLayout mainRowDataStorage;
    private LinearLayout mainRowChatSettings;
    private LinearLayout mainRowDevices;
    private LinearLayout mainRowProxy;
    private TextView tvMainProxyStatus;

    // Tab 3: Profile & Multi-Account Views
    private ImageView ivTabProfileAvatarCircle;
    private TextView tvTabProfileAvatarInitials;
    private ImageView ivTabProfileAvatarPhoto;
    private TextView tvTabProfileName;
    private TextView tvTabProfilePhone;
    private TextView tvTabProfileUsername;
    private Button btnTabProfileEdit;
    private RecyclerView rvTabProfileAccounts;
    private LinearLayout btnTabProfileAddAccount;
    private Button btnTabProfileLogout;
    private DrawerAccountAdapter accountSwitcherAdapter;

    // Chat Data & State
    private final ConcurrentHashMap<Long, TdApi.Chat> allChatsMap = new ConcurrentHashMap<>();
    private final List<TdApi.Chat> displayChats = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private TdLibAccountClient activeClient;
    private int currentFolderIndex = 0; // 0=All, 1=Personal, 2=Groups, 3=Channels, 4=Unread
    private String currentSearchQuery = "";
    private boolean isSearching = false;
    private int currentTabId = R.id.tab_chats;

    private final android.os.Handler filterDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable filterDebounceRunnable = this::applyFilterAndSort;
    private final TdLibAccountClient.RawUpdateListener rawUpdateListener = this::handleIncomingTdlibUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupWindowInsets();
        setupBottomNavigation();
        setupFolderTabs();
        setupSearch();
        setupRecyclerViews();
        setupSettingsTabClicks();
        setupProfileTabClicks();
    }

    private void initViews() {
        mainCoordinatorLayout = findViewById(R.id.mainCoordinatorLayout);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        layoutSearchBar = findViewById(R.id.layoutSearchBar);
        etSearchQuery = findViewById(R.id.etSearchQuery);
        btnCloseSearch = findViewById(R.id.btnCloseSearch);
        tabLayoutFolders = findViewById(R.id.tabLayoutFolders);
        bottomNav = findViewById(R.id.bottomNavMain);
        fabNewChat = findViewById(R.id.fabNewChat);

        tabContainerChats = findViewById(R.id.tabContainerChats);
        tabContainerContacts = findViewById(R.id.tabContainerContacts);
        tabContainerSettings = findViewById(R.id.tabContainerSettings);
        tabContainerProfile = findViewById(R.id.tabContainerProfile);

        // Chats Views
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        recyclerViewChats = findViewById(R.id.recyclerViewChats);
        progressBarChats = findViewById(R.id.progressBarChats);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);
        btnEmptyStateLogin = findViewById(R.id.btnEmptyStateLogin);

        // Contacts Views
        swipeRefreshContactsMain = findViewById(R.id.swipeRefreshContactsMain);
        rvContactsMain = findViewById(R.id.rvContactsMain);
        pbContactsMain = findViewById(R.id.pbContactsMain);
        layoutEmptyContactsMain = findViewById(R.id.layoutEmptyContactsMain);

        // Settings Views
        mainRowNotifications = findViewById(R.id.mainRowSettingNotifications);
        mainRowPrivacy = findViewById(R.id.mainRowSettingPrivacy);
        mainRowDataStorage = findViewById(R.id.mainRowSettingDataStorage);
        mainRowChatSettings = findViewById(R.id.mainRowSettingChatSettings);
        mainRowDevices = findViewById(R.id.mainRowSettingDevices);
        mainRowProxy = findViewById(R.id.mainRowSettingProxy);
        tvMainProxyStatus = findViewById(R.id.tvMainProxyStatus);

        // Profile Views
        ivTabProfileAvatarCircle = findViewById(R.id.ivTabProfileAvatarCircle);
        tvTabProfileAvatarInitials = findViewById(R.id.tvTabProfileAvatarInitials);
        ivTabProfileAvatarPhoto = findViewById(R.id.ivTabProfileAvatarPhoto);
        tvTabProfileName = findViewById(R.id.tvTabProfileName);
        tvTabProfilePhone = findViewById(R.id.tvTabProfilePhone);
        tvTabProfileUsername = findViewById(R.id.tvTabProfileUsername);
        btnTabProfileEdit = findViewById(R.id.btnTabProfileEdit);
        rvTabProfileAccounts = findViewById(R.id.rvTabProfileAccounts);
        btnTabProfileAddAccount = findViewById(R.id.btnTabProfileAddAccount);
        btnTabProfileLogout = findViewById(R.id.btnTabProfileLogout);

        btnEmptyStateLogin.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AuthActivity.class)));

        fabNewChat.setOnClickListener(v -> {
            if (currentTabId == R.id.tab_contacts) {
                showAddContactDialog();
            } else {
                Intent intent = new Intent(MainActivity.this, ContactsActivity.class);
                AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
                if (active != null) {
                    intent.putExtra("account_id", active.id);
                }
                startActivity(intent);
            }
        });

        swipeRefreshLayout.setColorSchemeColors(getColor(R.color.primary));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (activeClient != null) {
                activeClient.loadChats(new TdApi.ChatListMain(), 30, result -> runOnUiThread(() -> swipeRefreshLayout.setRefreshing(false)));
            } else {
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        swipeRefreshContactsMain.setColorSchemeColors(getColor(R.color.primary));
        swipeRefreshContactsMain.setOnRefreshListener(this::loadContactsTab);
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mainCoordinatorLayout, (v, windowInsets) -> windowInsets);
    }

    private void setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            currentTabId = id;
            switchTab(id);
            return true;
        });
    }

    private void switchTab(int tabId) {
        if (isSearching) {
            closeSearchMode();
        }

        tabContainerChats.setVisibility(tabId == R.id.tab_chats ? View.VISIBLE : View.GONE);
        tabContainerContacts.setVisibility(tabId == R.id.tab_contacts ? View.VISIBLE : View.GONE);
        tabContainerSettings.setVisibility(tabId == R.id.tab_settings ? View.VISIBLE : View.GONE);
        tabContainerProfile.setVisibility(tabId == R.id.tab_profile ? View.VISIBLE : View.GONE);

        tabLayoutFolders.setVisibility(tabId == R.id.tab_chats ? View.VISIBLE : View.GONE);

        if (tabId == R.id.tab_chats) {
            toolbar.setTitle(R.string.app_name);
            fabNewChat.setVisibility(View.VISIBLE);
            fabNewChat.setImageResource(R.drawable.ic_pencil_compose);
        } else if (tabId == R.id.tab_contacts) {
            toolbar.setTitle("Contacts");
            fabNewChat.setVisibility(View.VISIBLE);
            fabNewChat.setImageResource(R.drawable.ic_plus_circle);
            loadContactsTab();
        } else if (tabId == R.id.tab_settings) {
            toolbar.setTitle("Settings");
            fabNewChat.setVisibility(View.GONE);
            updateSettingsTab();
        } else if (tabId == R.id.tab_profile) {
            toolbar.setTitle("Profile");
            fabNewChat.setVisibility(View.GONE);
            updateProfileTab();
        }
    }

    private void setupFolderTabs() {
        tabLayoutFolders.addTab(tabLayoutFolders.newTab().setText("All"));
        tabLayoutFolders.addTab(tabLayoutFolders.newTab().setText("Personal"));
        tabLayoutFolders.addTab(tabLayoutFolders.newTab().setText("Groups"));
        tabLayoutFolders.addTab(tabLayoutFolders.newTab().setText("Channels"));
        tabLayoutFolders.addTab(tabLayoutFolders.newTab().setText("Unread"));

        tabLayoutFolders.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentFolderIndex = tab.getPosition();
                applyFilterAndSort();
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupSearch() {
        btnCloseSearch.setOnClickListener(v -> closeSearchMode());

        etSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString().trim() : "";
                applyFilterAndSort();

                if (!currentSearchQuery.isEmpty() && activeClient != null) {
                    activeClient.searchChats(currentSearchQuery, 20, result -> {
                        if (result instanceof TdApi.Chats) {
                            long[] ids = ((TdApi.Chats) result).chatIds;
                            for (long id : ids) {
                                if (!allChatsMap.containsKey(id)) {
                                    activeClient.getChat(id, null);
                                }
                            }
                        }
                    });
                    activeClient.searchPublicChats(currentSearchQuery, result -> {
                        if (result instanceof TdApi.Chats) {
                            long[] ids = ((TdApi.Chats) result).chatIds;
                            for (long id : ids) {
                                if (!allChatsMap.containsKey(id)) {
                                    activeClient.getChat(id, null);
                                }
                            }
                        }
                    });
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void setupRecyclerViews() {
        // Chats Adapter
        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new ChatAdapter(this, displayChats, activeClient, chat -> {
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            intent.putExtra("chat_id", chat.id);
            intent.putExtra("chat_title", chat.title != null ? chat.title : "Chat");
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            if (active != null) {
                intent.putExtra("account_id", active.id);
            }
            startActivity(intent);
        });
        recyclerViewChats.setAdapter(chatAdapter);

        // Contacts Adapter
        rvContactsMain.setLayoutManager(new LinearLayoutManager(this));
        contactAdapter = new ContactAdapter(this, activeClient, false, user -> {
            if (activeClient != null) {
                activeClient.createPrivateChat(user.id, res -> {
                    if (res instanceof TdApi.Chat) {
                        TdApi.Chat targetChat = (TdApi.Chat) res;
                        runOnUiThread(() -> {
                            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
                            intent.putExtra("chat_id", targetChat.id);
                            intent.putExtra("chat_title", targetChat.title);
                            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
                            if (active != null) intent.putExtra("account_id", active.id);
                            startActivity(intent);
                        });
                    }
                });
            }
        }, null);
        rvContactsMain.setAdapter(contactAdapter);

        // Multi-Account Switcher Adapter
        rvTabProfileAccounts.setLayoutManager(new LinearLayoutManager(this));
        accountSwitcherAdapter = new DrawerAccountAdapter(this, new DrawerAccountAdapter.OnAccountClickListener() {
            @Override
            public void onAccountClick(AccountManager.AccountItem account) {
                AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                if (manager != null && account != null) {
                    AccountManager.AccountItem current = manager.getActiveAccount();
                    if (current == null || !account.id.equals(current.id)) {
                        switchActiveAccount(account.id);
                    }
                }
            }

            @Override
            public void onAccountLongClick(AccountManager.AccountItem account) {
                confirmRemoveAccount(account);
            }
        });
        rvTabProfileAccounts.setAdapter(accountSwitcherAdapter);
    }

    private void setupSettingsTabClicks() {
        mainRowNotifications.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            Intent intent = new Intent(this, NotificationsSettingsActivity.class);
            if (active != null) intent.putExtra("account_id", active.id);
            startActivity(intent);
        });

        mainRowPrivacy.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            Intent intent = new Intent(this, PrivacySettingsActivity.class);
            if (active != null) intent.putExtra("account_id", active.id);
            startActivity(intent);
        });

        mainRowDataStorage.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            Intent intent = new Intent(this, DataStorageActivity.class);
            if (active != null) intent.putExtra("account_id", active.id);
            startActivity(intent);
        });

        mainRowChatSettings.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            Intent intent = new Intent(this, ChatSettingsActivity.class);
            if (active != null) intent.putExtra("account_id", active.id);
            startActivity(intent);
        });

        mainRowDevices.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            Intent intent = new Intent(this, DevicesActivity.class);
            if (active != null) intent.putExtra("account_id", active.id);
            startActivity(intent);
        });

        mainRowProxy.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            String targetAccountId = active != null ? active.id : AuthActivity.DEFAULT_ACCOUNT_ID;
            ProxyDialogHelper.showProxyDialog(this, targetAccountId, this::updateSettingsTab);
        });
    }

    private void setupProfileTabClicks() {
        btnTabProfileEdit.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            Intent intent = new Intent(this, ProfileActivity.class);
            if (active != null) intent.putExtra("account_id", active.id);
            startActivity(intent);
        });

        btnTabProfileAddAccount.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            String newAccountId = manager != null ? manager.generateNewAccountId() : "account_" + System.currentTimeMillis();
            Intent authIntent = new Intent(this, AuthActivity.class);
            authIntent.putExtra("account_id", newAccountId);
            startActivity(authIntent);
        });

        btnTabProfileLogout.setOnClickListener(v -> {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            if (active != null) {
                confirmRemoveAccount(active);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeClient != null) {
            activeClient.setOnline(true);
        }
        syncActiveAccountAndChats();
        if (currentTabId == R.id.tab_contacts) loadContactsTab();
        else if (currentTabId == R.id.tab_settings) updateSettingsTab();
        else if (currentTabId == R.id.tab_profile) updateProfileTab();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        filterDebounceHandler.removeCallbacks(filterDebounceRunnable);
        if (activeClient != null) {
            activeClient.removeRawUpdateListener(rawUpdateListener);
        }
    }

    private void syncActiveAccountAndChats() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;

        if (active == null) {
            showLoggedOutEmptyState();
            return;
        }

        layoutEmptyState.setVisibility(View.GONE);
        recyclerViewChats.setVisibility(View.VISIBLE);

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        if (tdManager == null) return;

        TdLibAccountClient newClient = tdManager.getOrCreateClient(active.id);
        if (this.activeClient != newClient) {
            if (this.activeClient != null) {
                this.activeClient.removeRawUpdateListener(rawUpdateListener);
            }
            this.activeClient = newClient;
            this.activeClient.addRawUpdateListener(rawUpdateListener);
            this.chatAdapter.setActiveClient(this.activeClient);
        }

        this.activeClient.setOnline(true);
        allChatsMap.clear();
        for (TdApi.Chat chat : this.activeClient.getKnownChats()) {
            allChatsMap.put(chat.id, chat);
        }
        applyFilterAndSort();

        loadChatsFromTdLib();
        updateProfileTab();
        updateSettingsTab();
    }

    private void loadChatsFromTdLib() {
        if (activeClient == null) return;
        if (allChatsMap.isEmpty()) progressBarChats.setVisibility(View.VISIBLE);

        activeClient.loadChats(new TdApi.ChatListMain(), 30, result -> runOnUiThread(() -> {
            progressBarChats.setVisibility(View.GONE);
            applyFilterAndSort();
        }));
    }

    private void loadContactsTab() {
        if (activeClient == null) {
            swipeRefreshContactsMain.setRefreshing(false);
            return;
        }
        pbContactsMain.setVisibility(View.VISIBLE);

        activeClient.getContacts(res -> {
            runOnUiThread(() -> {
                pbContactsMain.setVisibility(View.GONE);
                swipeRefreshContactsMain.setRefreshing(false);
            });
            if (res instanceof TdApi.Users) {
                long[] userIds = ((TdApi.Users) res).userIds;
                if (userIds.length == 0) {
                    runOnUiThread(() -> {
                        contactsList.clear();
                        contactAdapter.setUsers(contactsList);
                        layoutEmptyContactsMain.setVisibility(View.VISIBLE);
                        rvContactsMain.setVisibility(View.GONE);
                    });
                    return;
                }
                loadContactsUsers(userIds);
            }
        });
    }

    private void loadContactsUsers(long[] userIds) {
        List<TdApi.User> loaded = new ArrayList<>();
        int total = userIds.length;
        for (long id : userIds) {
            activeClient.getUser(id, obj -> {
                if (obj instanceof TdApi.User) {
                    synchronized (loaded) {
                        loaded.add((TdApi.User) obj);
                        if (loaded.size() == total) {
                            runOnUiThread(() -> {
                                contactsList.clear();
                                contactsList.addAll(loaded);
                                contactAdapter.setUsers(contactsList);
                                layoutEmptyContactsMain.setVisibility(View.GONE);
                                rvContactsMain.setVisibility(View.VISIBLE);
                            });
                        }
                    }
                }
            });
        }
    }

    private void updateSettingsTab() {
        ProxyController proxy = NexGramApplication.getInstance().getProxyController();
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        String targetId = active != null ? active.id : AuthActivity.ACCOUNT_ID;
        ProxyController.ProxyMetadata meta = (proxy != null) ? proxy.getProxyMetadata(targetId) : null;

        if (meta != null && meta.enabled && meta.type != ProxyController.ProxyType.DIRECT) {
            String latency = meta.lastLatencyMs > 0 ? " (" + meta.lastLatencyMs + " ms)" : "";
            tvMainProxyStatus.setText("Connected: " + meta.type + " " + meta.host + ":" + meta.port + latency);
        } else {
            tvMainProxyStatus.setText("Direct Connection (Proxy Disabled)");
        }
    }

    private void updateProfileTab() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        if (active != null) {
            tvTabProfileName.setText(active.name != null && !active.name.isEmpty() ? active.name : "Telegram User");
            tvTabProfilePhone.setText(active.phone != null && !active.phone.isEmpty() ? active.phone : "No Phone");
            tvTabProfileUsername.setText(active.username != null && !active.username.isEmpty() ? "@" + active.username : "Set Username");

            tvTabProfileAvatarInitials.setText(TelegramAvatarHelper.getInitials(active.name));
            ivTabProfileAvatarCircle.setColorFilter(TelegramAvatarHelper.getAvatarColor(Math.abs((active.name != null ? active.name : active.id).hashCode())));

            if (active.avatarPath != null && !active.avatarPath.isEmpty() && new File(active.avatarPath).exists()) {
                try {
                    ivTabProfileAvatarPhoto.setImageBitmap(BitmapFactory.decodeFile(active.avatarPath));
                    ivTabProfileAvatarPhoto.setVisibility(View.VISIBLE);
                    tvTabProfileAvatarInitials.setVisibility(View.GONE);
                } catch (Exception ignored) {
                    ivTabProfileAvatarPhoto.setVisibility(View.GONE);
                    tvTabProfileAvatarInitials.setVisibility(View.VISIBLE);
                }
            } else {
                ivTabProfileAvatarPhoto.setVisibility(View.GONE);
                tvTabProfileAvatarInitials.setVisibility(View.VISIBLE);
            }

            if (activeClient != null) {
                activeClient.getMe(res -> {
                    if (res instanceof TdApi.User) {
                        TdApi.User user = (TdApi.User) res;
                        runOnUiThread(() -> {
                            String fName = user.firstName != null ? user.firstName : "";
                            String lName = user.lastName != null ? user.lastName : "";
                            String full = (fName + " " + lName).trim();
                            if (!full.isEmpty()) tvTabProfileName.setText(full);
                            if (user.phoneNumber != null) tvTabProfilePhone.setText("+" + user.phoneNumber);
                            if (user.usernames != null && user.usernames.activeUsernames.length > 0) {
                                tvTabProfileUsername.setText("@" + user.usernames.activeUsernames[0]);
                            }
                        });
                    }
                });
            }

            List<AccountManager.AccountItem> all = manager.getAllAccounts();
            accountSwitcherAdapter.updateAccounts(all, active.id);
        }
    }

    private void switchActiveAccount(String newAccountId) {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        if (manager == null || newAccountId == null) return;

        manager.switchAccount(newAccountId);

        if (activeClient != null) {
            activeClient.removeRawUpdateListener(rawUpdateListener);
            activeClient = null;
        }

        allChatsMap.clear();
        displayChats.clear();
        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        ProxyController proxyController = NexGramApplication.getInstance().getProxyController();
        if (tdManager != null) {
            TdLibAccountClient newClient = tdManager.getOrCreateClient(newAccountId);
            if (proxyController != null) {
                newClient.applySavedProxy(proxyController, null);
            }
        }

        syncActiveAccountAndChats();
    }

    private void confirmRemoveAccount(AccountManager.AccountItem account) {
        if (account == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Remove Account")
                .setMessage("Are you sure you want to log out from " + account.name + "?")
                .setPositiveButton("Log Out", (dialog, which) -> {
                    TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
                    if (tdManager != null) {
                        TdLibAccountClient client = tdManager.getClient(account.id);
                        if (client != null) {
                            client.logOut(result -> client.deleteDatabaseFiles());
                        }
                        tdManager.closeClient(account.id);
                    }
                    AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                    if (manager != null) {
                        manager.removeAccount(account.id);
                    }
                    syncActiveAccountAndChats();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddContactDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);
        EditText etFirst = dialogView.findViewById(R.id.etAddContactFirst);
        EditText etLast = dialogView.findViewById(R.id.etAddContactLast);
        EditText etPhone = dialogView.findViewById(R.id.etAddContactPhone);

        new AlertDialog.Builder(this)
                .setTitle("Add Contact")
                .setView(dialogView)
                .setPositiveButton("Add", (dialog, which) -> {
                    String first = etFirst.getText().toString().trim();
                    String last = etLast.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();
                    if (phone.isEmpty()) {
                        Toast.makeText(this, "Phone number is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (first.isEmpty()) first = phone;
                    if (!phone.startsWith("+")) phone = "+" + phone;

                    TdApi.ImportedContact importedContact = new TdApi.ImportedContact(phone, first, last, null);
                    if (activeClient != null) {
                        activeClient.send(new TdApi.ImportContacts(new TdApi.ImportedContact[]{importedContact}), res -> {
                            runOnUiThread(() -> {
                                if (res instanceof TdApi.ImportedContacts) {
                                    Toast.makeText(this, "Contact added", Toast.LENGTH_SHORT).show();
                                    loadContactsTab();
                                } else if (res instanceof TdApi.Error) {
                                    Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
                                }
                            });
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleIncomingTdlibUpdate(TdApi.Object update) {
        if (update instanceof TdApi.UpdateNewChat) {
            TdApi.Chat chat = ((TdApi.UpdateNewChat) update).chat;
            allChatsMap.put(chat.id, chat);
            postApplyFilter();
        } else if (update instanceof TdApi.UpdateChatPosition) {
            TdApi.UpdateChatPosition p = (TdApi.UpdateChatPosition) update;
            TdApi.Chat chat = allChatsMap.get(p.chatId);
            if (chat == null && activeClient != null) {
                chat = activeClient.getKnownChat(p.chatId);
                if (chat != null) allChatsMap.put(chat.id, chat);
            }
            if (chat != null) {
                updateChatPosition(chat, p.position);
                postApplyFilter();
            } else if (activeClient != null) {
                activeClient.getChat(p.chatId, res -> {
                    if (res instanceof TdApi.Chat) {
                        allChatsMap.put(((TdApi.Chat) res).id, (TdApi.Chat) res);
                        postApplyFilter();
                    }
                });
            }
        } else if (update instanceof TdApi.UpdateChatLastMessage) {
            TdApi.UpdateChatLastMessage m = (TdApi.UpdateChatLastMessage) update;
            TdApi.Chat chat = allChatsMap.get(m.chatId);
            if (chat != null) {
                chat.lastMessage = m.lastMessage;
                if (m.positions != null && m.positions.length > 0) {
                    chat.positions = m.positions;
                }
                postApplyFilter();
            }
        } else if (update instanceof TdApi.UpdateChatDraftMessage) {
            TdApi.UpdateChatDraftMessage d = (TdApi.UpdateChatDraftMessage) update;
            TdApi.Chat chat = allChatsMap.get(d.chatId);
            if (chat != null) {
                chat.draftMessage = d.draftMessage;
                if (d.positions != null && d.positions.length > 0) {
                    chat.positions = d.positions;
                }
                postApplyFilter();
            }
        } else if (update instanceof TdApi.UpdateChatReadInbox) {
            TdApi.UpdateChatReadInbox r = (TdApi.UpdateChatReadInbox) update;
            TdApi.Chat chat = allChatsMap.get(r.chatId);
            if (chat != null) {
                chat.lastReadInboxMessageId = r.lastReadInboxMessageId;
                chat.unreadCount = r.unreadCount;
                postApplyFilter();
            }
        } else if (update instanceof TdApi.UpdateChatReadOutbox) {
            TdApi.UpdateChatReadOutbox r = (TdApi.UpdateChatReadOutbox) update;
            TdApi.Chat chat = allChatsMap.get(r.chatId);
            if (chat != null) {
                chat.lastReadOutboxMessageId = r.lastReadOutboxMessageId;
                postApplyFilter();
            }
        } else if (update instanceof TdApi.UpdateChatTitle) {
            TdApi.UpdateChatTitle t = (TdApi.UpdateChatTitle) update;
            TdApi.Chat chat = allChatsMap.get(t.chatId);
            if (chat != null) {
                chat.title = t.title;
                postApplyFilter();
            }
        } else if (update instanceof TdApi.UpdateChatPhoto) {
            TdApi.UpdateChatPhoto p = (TdApi.UpdateChatPhoto) update;
            TdApi.Chat chat = allChatsMap.get(p.chatId);
            if (chat != null) {
                chat.photo = p.photo;
                postApplyFilter();
            }
        }
    }

    private void updateChatPosition(TdApi.Chat chat, TdApi.ChatPosition newPos) {
        if (chat == null || newPos == null) return;
        if (chat.positions == null) {
            chat.positions = new TdApi.ChatPosition[]{newPos};
            return;
        }
        for (int i = 0; i < chat.positions.length; i++) {
            if (chat.positions[i].list != null && newPos.list != null &&
                    chat.positions[i].list.getConstructor() == newPos.list.getConstructor()) {
                chat.positions[i] = newPos;
                return;
            }
        }
        TdApi.ChatPosition[] updated = new TdApi.ChatPosition[chat.positions.length + 1];
        System.arraycopy(chat.positions, 0, updated, 0, chat.positions.length);
        updated[chat.positions.length] = newPos;
        chat.positions = updated;
    }

    private void postApplyFilter() {
        filterDebounceHandler.removeCallbacks(filterDebounceRunnable);
        filterDebounceHandler.postDelayed(filterDebounceRunnable, 80);
    }

    private void applyFilterAndSort() {
        List<TdApi.Chat> filtered = new ArrayList<>();
        String query = currentSearchQuery.toLowerCase().trim();

        for (TdApi.Chat chat : allChatsMap.values()) {
            if (!query.isEmpty()) {
                String title = chat.title != null ? chat.title.toLowerCase() : "";
                if (!title.contains(query)) continue;
            }

            switch (currentFolderIndex) {
                case 1:
                    if (!(chat.type instanceof TdApi.ChatTypePrivate || chat.type instanceof TdApi.ChatTypeSecret)) continue;
                    break;
                case 2:
                    if (!(chat.type instanceof TdApi.ChatTypeBasicGroup ||
                            (chat.type instanceof TdApi.ChatTypeSupergroup && !((TdApi.ChatTypeSupergroup) chat.type).isChannel))) continue;
                    break;
                case 3:
                    if (!(chat.type instanceof TdApi.ChatTypeSupergroup && ((TdApi.ChatTypeSupergroup) chat.type).isChannel)) continue;
                    break;
                case 4:
                    if (chat.unreadCount <= 0) continue;
                    break;
                case 0:
                default:
                    break;
            }
            filtered.add(chat);
        }

        Collections.sort(filtered, (c1, c2) -> Long.compare(getChatOrder(c2), getChatOrder(c1)));

        displayChats.clear();
        displayChats.addAll(filtered);
        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
    }

    private long getChatOrder(TdApi.Chat chat) {
        if (chat == null || chat.positions == null) return 0;
        for (TdApi.ChatPosition pos : chat.positions) {
            if (pos.list instanceof TdApi.ChatListMain) return pos.order;
        }
        return 0;
    }

    private void showLoggedOutEmptyState() {
        recyclerViewChats.setVisibility(View.GONE);
        progressBarChats.setVisibility(View.GONE);
        layoutEmptyState.setVisibility(View.VISIBLE);
        tvEmptyTitle.setText(R.string.empty_state_title);
        tvEmptySubtitle.setText(R.string.empty_state_desc);
        btnEmptyStateLogin.setVisibility(View.VISIBLE);
        fabNewChat.setVisibility(View.GONE);
    }

    private void openSearchMode() {
        isSearching = true;
        layoutSearchBar.setVisibility(View.VISIBLE);
        toolbar.setTitle("");
        etSearchQuery.requestFocus();
    }

    private void closeSearchMode() {
        isSearching = false;
        currentSearchQuery = "";
        etSearchQuery.setText("");
        layoutSearchBar.setVisibility(View.GONE);
        toolbar.setTitle(currentTabId == R.id.tab_contacts ? "Contacts" :
                currentTabId == R.id.tab_settings ? "Settings" :
                        currentTabId == R.id.tab_profile ? "Profile" : getString(R.string.app_name));
        applyFilterAndSort();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_overflow_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        String activeId = active != null ? active.id : null;

        if (id == R.id.action_search) {
            openSearchMode();
            return true;
        } else if (id == R.id.action_new_group) {
            Intent intent = new Intent(this, NewGroupActivity.class);
            if (activeId != null) intent.putExtra("account_id", activeId);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_new_channel) {
            Intent intent = new Intent(this, NewChannelActivity.class);
            if (activeId != null) intent.putExtra("account_id", activeId);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_saved_messages) {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("chat_title", "Saved Messages");
            if (activeId != null) intent.putExtra("account_id", activeId);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_proxy) {
            String targetAccountId = activeId != null ? activeId : AuthActivity.DEFAULT_ACCOUNT_ID;
            ProxyDialogHelper.showProxyDialog(this, targetAccountId, this::updateSettingsTab);
            return true;
        } else if (id == R.id.action_night_mode) {
            toggleNightMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleNightMode() {
        int current = AppCompatDelegate.getDefaultNightMode();
        int newMode = (current == AppCompatDelegate.MODE_NIGHT_YES)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;
        getSharedPreferences(ChatSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(ChatSettingsActivity.KEY_THEME_MODE, newMode).apply();
        AppCompatDelegate.setDefaultNightMode(newMode);
    }

    @Override
    public void onBackPressed() {
        if (isSearching) {
            closeSearchMode();
        } else if (currentTabId != R.id.tab_chats) {
            bottomNav.setSelectedItemId(R.id.tab_chats);
        } else {
            super.onBackPressed();
        }
    }
}
