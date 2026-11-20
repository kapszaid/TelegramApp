package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Primary Native Activity for NexGram
 * Displays chat list, multi-account switcher, and navigation controls.
 *
 * ZERO FAKE CHATS POLICY:
 * Does not show fake Telegram mock chats or fake notifications.
 * Renders an accurate empty state when no accounts or dialogs exist.
 */
public class MainActivity extends AppCompatActivity {

    public static class ChatSummary {
        public long id;
        public String title;
        public String lastMessage;
        public String time;
        public int unreadCount;

        public ChatSummary(long id, String title, String lastMessage, String time, int unreadCount) {
            this.id = id;
            this.title = title;
            this.lastMessage = lastMessage;
            this.time = time;
            this.unreadCount = unreadCount;
        }
    }

    private TextView tvActiveAccountName;
    private Button btnSwitchAccount;
    private RecyclerView recyclerViewChats;
    private View layoutEmptyState;
    private ChatAdapter chatAdapter;
    private final List<ChatSummary> chatList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvActiveAccountName = findViewById(R.id.tvActiveAccountName);
        btnSwitchAccount = findViewById(R.id.btnSwitchAccount);
        recyclerViewChats = findViewById(R.id.recyclerViewChats);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);

        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new ChatAdapter(chatList, chat -> {
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            intent.putExtra("chat_id", chat.id);
            intent.putExtra("chat_title", chat.title);
            startActivity(intent);
        });
        recyclerViewChats.setAdapter(chatAdapter);

        btnSwitchAccount.setOnClickListener(v -> showAccountSwitcherDialog());

        loadAccountAndData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAccountAndData();
    }

    private void loadAccountAndData() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;

        if (active != null) {
            tvActiveAccountName.setText(active.name + " (" + active.phone + ")");
            btnSwitchAccount.setVisibility(manager.getAccountCount() > 1 ? View.VISIBLE : View.GONE);
        } else {
            tvActiveAccountName.setText("No Account Connected");
            btnSwitchAccount.setVisibility(View.GONE);
        }

        // STRICT ACCURACY: No fake chats inserted!
        // Dialog list stays empty until live MTProto dialog synchronization is connected.
        chatList.clear();
        chatAdapter.notifyDataSetChanged();

        if (chatList.isEmpty()) {
            recyclerViewChats.setVisibility(View.GONE);
            layoutEmptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerViewChats.setVisibility(View.VISIBLE);
            layoutEmptyState.setVisibility(View.GONE);
        }
    }

    private void showAccountSwitcherDialog() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        if (manager == null) return;
        List<AccountManager.AccountItem> accounts = manager.getAllAccounts();

        if (accounts.isEmpty()) {
            Toast.makeText(this, "No other accounts authorized. Add an account in Settings.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            names[i] = accounts.get(i).name + " (" + accounts.get(i).phone + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.title_accounts)
                .setItems(names, (dialog, which) -> {
                    manager.switchAccount(accounts.get(which).id);
                    loadAccountAndData();
                    Toast.makeText(this, "Switched to " + accounts.get(which).name, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.title_settings)
            .setIcon(android.R.drawable.ic_menu_preferences)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static class ChatAdapter extends RecyclerView.Adapter<ChatViewHolder> {
        interface OnChatClickListener {
            void onChatClick(ChatSummary chat);
        }

        private final List<ChatSummary> items;
        private final OnChatClickListener listener;

        public ChatAdapter(List<ChatSummary> items, OnChatClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat, parent, false);
            return new ChatViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
            ChatSummary chat = items.get(position);
            holder.tvTitle.setText(chat.title);
            holder.tvMessage.setText(chat.lastMessage);
            holder.tvTime.setText(chat.time);
            if (chat.unreadCount > 0) {
                holder.tvBadge.setText(String.valueOf(chat.unreadCount));
                holder.tvBadge.setVisibility(View.VISIBLE);
            } else {
                holder.tvBadge.setVisibility(View.GONE);
            }
            holder.itemView.setOnClickListener(v -> listener.onChatClick(chat));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMessage, tvTime, tvBadge;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvChatTitle);
            tvMessage = itemView.findViewById(R.id.tvLastMessage);
            tvTime = itemView.findViewById(R.id.tvChatTime);
            tvBadge = itemView.findViewById(R.id.tvUnreadBadge);
        }
    }
}
