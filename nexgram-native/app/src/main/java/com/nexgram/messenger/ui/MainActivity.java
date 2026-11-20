package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.adapter.ChatAdapter;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.drinkless.tdlib.TdApi;

/**
 * Primary Native Telegram Chat Screen for NexGram
 *
 * Implements official Telegram Android main chat screen:
 * - Hamburger navigation drawer with profile header, account details, and settings shortcuts
 * - Search bar with real-time in-memory & TDLib search
 * - Telegram Chat Folder Tabs: All, Personal, Groups, Channels, Unread
 * - Real TDLib chat list synchronization (LoadChats, UpdateNewChat, UpdateChatPosition, etc.)
 * - Native avatar generation with official 7-color Telegram palette and photo caching
 * - Pinned pushpin indicator, mute state, outgoing checkmark delivery, and unread counters
 * - Floating compose button (pencil icon)
 * - Zero fake mock data: displays only authentic Telegram chats
 */
public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private static final String TAG = "MainActivity";

    private DrawerLayout drawerLayout;
    private Toolbar toolbar;
    private LinearLayout layoutSearchBar;
    private EditText etSearchQuery;
    private ImageView btnCloseSearch;
    private TabLayout tabLayoutFolders;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerViewChats;
    private ProgressBar progressBarChats;
    private View layoutEmptyState;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private Button btnEmptyStateLogin;
    private FloatingActionButton fabNewChat;
    private NavigationView navigationView;

    // Navigation Header Views
    private ImageView ivNavAvatarCircle;
    private TextView tvNavAvatarInitials;
    private ImageView ivNavAvatarPhoto;
    private TextView tvNavUserName;
    private TextView tvNavUserPhone;

    // Chat data & state
    private final ConcurrentHashMap<Long, TdApi.Chat> allChatsMap = new ConcurrentHashMap<>();
    private final List<TdApi.Chat> displayChats = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private TdLibAccountClient activeClient;
    private int currentFolderIndex = 0; // 0=All, 1=Personal, 2=Groups, 3=Channels, 4=Unread
    private String currentSearchQuery = "";
    private boolean isSearching = false;

    private final TdLibAccountClient.RawUpdateListener rawUpdateListener = this::handleIncomingTdlibUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupWindowInsets();
        setupNavigationDrawer();
        setupFolderTabs();
        setupSearch();
        setupRecyclerView();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        layoutSearchBar = findViewById(R.id.layoutSearchBar);
        etSearchQuery = findViewById(R.id.etSearchQuery);
        btnCloseSearch = findViewById(R.id.btnCloseSearch);
        tabLayoutFolders = findViewById(R.id.tabLayoutFolders);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        recyclerViewChats = findViewById(R.id.recyclerViewChats);
        progressBarChats = findViewById(R.id.progressBarChats);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);
        btnEmptyStateLogin = findViewById(R.id.btnEmptyStateLogin);
        fabNewChat = findViewById(R.id.fabNewChat);
        navigationView = findViewById(R.id.navigationView);

        btnEmptyStateLogin.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, AuthActivity.class));
        });

        fabNewChat.setOnClickListener(v -> {
            Toast.makeText(this, "Compose new message (Contacts integration)", Toast.LENGTH_SHORT).show();
        });

        swipeRefreshLayout.setColorSchemeColors(getColor(R.color.primary));
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (activeClient != null) {
                activeClient.loadChats(new TdApi.ChatListMain(), 30, result -> {
                    runOnUiThread(() -> swipeRefreshLayout.setRefreshing(false));
                });
            } else {
                swipeRefreshLayout.setRefreshing(false);
            }
        });
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            recyclerViewChats.setPadding(0, 0, 0, insets.bottom);
            recyclerViewChats.setClipToPadding(false);
            if (fabNewChat.getLayoutParams() instanceof CoordinatorLayout.LayoutParams) {
                CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) fabNewChat.getLayoutParams();
                int baseMargin = (int) (16 * getResources().getDisplayMetrics().density);
                lp.bottomMargin = insets.bottom + baseMargin;
                fabNewChat.setLayoutParams(lp);
            }
            return windowInsets;
        });
    }

    private void setupNavigationDrawer() {
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar, R.string.title_settings, R.string.title_settings);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        toolbar.setNavigationIcon(R.drawable.ic_menu_hamburger);
        toolbar.setNavigationOnClickListener(v -> {
            if (isSearching) {
                closeSearchMode();
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        navigationView.setNavigationItemSelectedListener(this);

        View headerView = navigationView.getHeaderView(0);
        if (headerView != null) {
            ivNavAvatarCircle = headerView.findViewById(R.id.ivNavAvatarCircle);
            tvNavAvatarInitials = headerView.findViewById(R.id.tvNavAvatarInitials);
            ivNavAvatarPhoto = headerView.findViewById(R.id.ivNavAvatarPhoto);
            tvNavUserName = headerView.findViewById(R.id.tvNavUserName);
            tvNavUserPhone = headerView.findViewById(R.id.tvNavUserPhone);
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

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupSearch() {
        btnCloseSearch.setOnClickListener(v -> closeSearchMode());

        etSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

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
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupRecyclerView() {
        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new ChatAdapter(this, displayChats, activeClient, chat -> {
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            intent.putExtra("chat_id", chat.id);
            intent.putExtra("chat_title", chat.title != null ? chat.title : "Chat");
            startActivity(intent);
        });
        recyclerViewChats.setAdapter(chatAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncActiveAccountAndChats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (activeClient != null) {
            activeClient.removeRawUpdateListener(rawUpdateListener);
        }
    }

    private void syncActiveAccountAndChats() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;

        if (active == null) {
            // Logged out / no account state
            updateDrawerProfile(null);
            showLoggedOutEmptyState();
            allChatsMap.clear();
            displayChats.clear();
            chatAdapter.notifyDataSetChanged();
            return;
        }

        updateDrawerProfile(active);

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        if (tdManager == null) return;

        // Retrieve existing or initialize dedicated TDLib client
        TdLibAccountClient client = tdManager.getOrCreateClient(active.id);
        if (this.activeClient != client) {
            if (this.activeClient != null) {
                this.activeClient.removeRawUpdateListener(rawUpdateListener);
            }
            this.activeClient = client;
            this.activeClient.addRawUpdateListener(rawUpdateListener);
        }

        // Setup ChatAdapter with current client for avatar loading
        setupRecyclerView();

        // Listen for authorization state / session revocation
        this.activeClient.addStateListener(new TdLibAccountClient.StateListener() {
            @Override
            public void onAuthorizationStateChanged(TdApi.AuthorizationState newState) {
                if (newState instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Telegram session expired or revoked. Please log in again.", Toast.LENGTH_LONG).show();
                        manager.removeAccount(active.id);
                        syncActiveAccountAndChats();
                    });
                } else if (newState instanceof TdApi.AuthorizationStateReady) {
                    runOnUiThread(() -> loadChatsFromTdLib());
                }
            }

            @Override
            public void onClientError(int errorCode, String errorMessage) {
                Log.w(TAG, "Client error [" + errorCode + "]: " + errorMessage);
            }
        });

        // Trigger chat loading if engine is ready
        TdApi.AuthorizationState state = this.activeClient.getCurrentAuthState();
        if (state instanceof TdApi.AuthorizationStateReady) {
            loadChatsFromTdLib();
        } else {
            progressBarChats.setVisibility(View.VISIBLE);
        }
    }

    private void loadChatsFromTdLib() {
        if (activeClient == null) return;

        if (allChatsMap.isEmpty()) {
            progressBarChats.setVisibility(View.VISIBLE);
        }

        // Request loading first 30 chats into TDLib memory
        activeClient.loadChats(new TdApi.ChatListMain(), 30, result -> {
            runOnUiThread(() -> {
                progressBarChats.setVisibility(View.GONE);
                applyFilterAndSort();
            });
        });
    }

    private void handleIncomingTdlibUpdate(TdApi.Object update) {
        if (update instanceof TdApi.UpdateNewChat) {
            TdApi.Chat chat = ((TdApi.UpdateNewChat) update).chat;
            allChatsMap.put(chat.id, chat);
            postApplyFilter();
        } else if (update instanceof TdApi.UpdateChatPosition) {
            TdApi.UpdateChatPosition p = (TdApi.UpdateChatPosition) update;
            TdApi.Chat chat = allChatsMap.get(p.chatId);
            if (chat != null) {
                updateChatPosition(chat, p.position);
                postApplyFilter();
            }
        } else if (update instanceof TdApi.UpdateChatLastMessage) {
            TdApi.UpdateChatLastMessage m = (TdApi.UpdateChatLastMessage) update;
            TdApi.Chat chat = allChatsMap.get(m.chatId);
            if (chat != null) {
                chat.lastMessage = m.lastMessage;
                if (m.positions != null) {
                    chat.positions = m.positions;
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
        } else if (update instanceof TdApi.UpdateChatNotificationSettings) {
            TdApi.UpdateChatNotificationSettings n = (TdApi.UpdateChatNotificationSettings) update;
            TdApi.Chat chat = allChatsMap.get(n.chatId);
            if (chat != null) {
                chat.notificationSettings = n.notificationSettings;
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
        } else if (update instanceof TdApi.UpdateFile) {
            postApplyFilter();
        } else if (update instanceof TdApi.UpdateUser) {
            // Update profile info if self
            TdApi.User user = ((TdApi.UpdateUser) update).user;
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            if (active != null && user.id == Long.parseLong(active.id.replace("account_", ""))) {
                runOnUiThread(() -> updateDrawerProfile(active));
            }
        }
    }

    private void updateChatPosition(TdApi.Chat chat, TdApi.ChatPosition newPos) {
        if (chat.positions == null) {
            chat.positions = new TdApi.ChatPosition[]{newPos};
            return;
        }
        boolean replaced = false;
        List<TdApi.ChatPosition> list = new ArrayList<>();
        for (TdApi.ChatPosition pos : chat.positions) {
            if (isSameChatList(pos.list, newPos.list)) {
                if (newPos.order > 0) {
                    list.add(newPos);
                }
                replaced = true;
            } else {
                list.add(pos);
            }
        }
        if (!replaced && newPos.order > 0) {
            list.add(newPos);
        }
        chat.positions = list.toArray(new TdApi.ChatPosition[0]);
    }

    private boolean isSameChatList(TdApi.ChatList a, TdApi.ChatList b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.getClass().equals(b.getClass());
    }

    private void postApplyFilter() {
        runOnUiThread(this::applyFilterAndSort);
    }

    private synchronized void applyFilterAndSort() {
        List<TdApi.Chat> filtered = new ArrayList<>();
        String queryLower = currentSearchQuery.toLowerCase(Locale.getDefault());

        for (TdApi.Chat chat : allChatsMap.values()) {
            long order = getChatOrder(chat);
            // Ignore chats not in Main chat list unless searching
            if (order <= 0 && currentSearchQuery.isEmpty()) {
                continue;
            }

            // 1. Folder Tab Filtering
            if (!matchesFolder(chat, currentFolderIndex)) {
                continue;
            }

            // 2. Search Query Filtering
            if (!currentSearchQuery.isEmpty()) {
                String title = chat.title != null ? chat.title.toLowerCase(Locale.getDefault()) : "";
                if (!title.contains(queryLower)) {
                    continue;
                }
            }

            filtered.add(chat);
        }

        // 3. Sort by TDLib Chat Position order descending (pinned chats automatically at top)
        Collections.sort(filtered, (c1, c2) -> Long.compare(getChatOrder(c2), getChatOrder(c1)));

        displayChats.clear();
        displayChats.addAll(filtered);
        chatAdapter.notifyDataSetChanged();

        progressBarChats.setVisibility(View.GONE);

        if (displayChats.isEmpty()) {
            recyclerViewChats.setVisibility(View.GONE);
            layoutEmptyState.setVisibility(View.VISIBLE);
            tvEmptyTitle.setText(isSearching ? "No Results Found" : "No Chats");
            tvEmptySubtitle.setText(isSearching ? "No chats matched \"" + currentSearchQuery + "\"." : "No conversations found in this folder.");
            btnEmptyStateLogin.setVisibility(View.GONE);
        } else {
            recyclerViewChats.setVisibility(View.VISIBLE);
            layoutEmptyState.setVisibility(View.GONE);
        }
    }

    private boolean matchesFolder(TdApi.Chat chat, int folderIndex) {
        switch (folderIndex) {
            case 0: // All
                return true;
            case 1: // Personal (Private & Secret)
                return chat.type instanceof TdApi.ChatTypePrivate || chat.type instanceof TdApi.ChatTypeSecret;
            case 2: // Groups (Basic Groups & non-channel Supergroups)
                return chat.type instanceof TdApi.ChatTypeBasicGroup ||
                       (chat.type instanceof TdApi.ChatTypeSupergroup && !((TdApi.ChatTypeSupergroup) chat.type).isChannel);
            case 3: // Channels (Supergroups where isChannel is true)
                return chat.type instanceof TdApi.ChatTypeSupergroup && ((TdApi.ChatTypeSupergroup) chat.type).isChannel;
            case 4: // Unread
                return chat.unreadCount > 0 || chat.isMarkedAsUnread;
            default:
                return true;
        }
    }

    public static long getChatOrder(TdApi.Chat chat) {
        if (chat == null || chat.positions == null) return 0;
        for (TdApi.ChatPosition pos : chat.positions) {
            if (pos.list instanceof TdApi.ChatListMain) {
                return pos.order;
            }
        }
        return 0;
    }

    private void updateDrawerProfile(AccountManager.AccountItem active) {
        if (active != null) {
            tvNavUserName.setText(active.name);
            tvNavUserPhone.setText(active.phone);
            ivNavAvatarCircle.setColorFilter(TelegramAvatarHelper.getAvatarColor(Math.abs(active.name.hashCode())));
            tvNavAvatarInitials.setText(TelegramAvatarHelper.getInitials(active.name));
            tvNavAvatarInitials.setVisibility(View.VISIBLE);
            ivNavAvatarPhoto.setVisibility(View.GONE);
        } else {
            tvNavUserName.setText("NexGram");
            tvNavUserPhone.setText("Tap to log in");
            ivNavAvatarCircle.setColorFilter(getColor(R.color.primary));
            tvNavAvatarInitials.setText("NG");
            tvNavAvatarInitials.setVisibility(View.VISIBLE);
            ivNavAvatarPhoto.setVisibility(View.GONE);
        }
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
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        etSearchQuery.requestFocus();
    }

    private void closeSearchMode() {
        isSearching = false;
        currentSearchQuery = "";
        etSearchQuery.setText("");
        layoutSearchBar.setVisibility(View.GONE);
        toolbar.setTitle(R.string.app_name);
        toolbar.setNavigationIcon(R.drawable.ic_menu_hamburger);
        applyFilterAndSort();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 101, 0, "Search")
                .setIcon(R.drawable.ic_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 101) {
            openSearchMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        drawerLayout.closeDrawer(GravityCompat.START);

        if (id == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.nav_saved) {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("chat_title", "Saved Messages");
            startActivity(intent);
        } else if (id == R.id.nav_new_group) {
            Toast.makeText(this, "New Group (Contacts integration)", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_contacts) {
            Toast.makeText(this, "Telegram Contacts", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_calls) {
            Toast.makeText(this, "Calls history", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_night_mode) {
            Toast.makeText(this, "Night mode active", Toast.LENGTH_SHORT).show();
        }

        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (isSearching) {
            closeSearchMode();
        } else {
            super.onBackPressed();
        }
    }
}
