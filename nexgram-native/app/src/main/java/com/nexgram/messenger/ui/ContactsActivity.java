package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.adapter.ContactAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.drinkless.tdlib.TdApi;

public class ContactsActivity extends AppCompatActivity {
    private static final String TAG = "ContactsActivity";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView rvContacts;
    private ProgressBar progressBar;
    private View layoutEmpty;
    private FloatingActionButton fabAddContact;

    private LinearLayout layoutSearch;
    private EditText etSearch;
    private ImageView btnCloseSearch;

    private ContactAdapter adapter;
    private final ConcurrentHashMap<Long, TdApi.User> contactsMap = new ConcurrentHashMap<>();
    private final List<TdApi.User> displayUsers = new ArrayList<>();
    private String currentSearchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        initViews();
        resolveAccountClient();
        setupRecyclerView();
        loadContacts();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarContacts);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        swipeRefresh = findViewById(R.id.swipeRefreshContacts);
        rvContacts = findViewById(R.id.rvContacts);
        progressBar = findViewById(R.id.pbContacts);
        layoutEmpty = findViewById(R.id.layoutEmptyContacts);
        fabAddContact = findViewById(R.id.fabAddContact);

        layoutSearch = findViewById(R.id.layoutContactSearch);
        etSearch = findViewById(R.id.etContactSearchQuery);
        btnCloseSearch = findViewById(R.id.btnCloseContactSearch);

        swipeRefresh.setColorSchemeColors(getColor(R.color.primary));
        swipeRefresh.setOnRefreshListener(this::loadContacts);

        fabAddContact.setOnClickListener(v -> showAddContactDialog());

        btnCloseSearch.setOnClickListener(v -> closeSearch());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s != null ? s.toString().trim().toLowerCase(Locale.getDefault()) : "";
                applyFilterAndSort();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void resolveAccountClient() {
        accountId = getIntent().getStringExtra("account_id");
        AccountManager accManager = NexGramApplication.getInstance().getAccountManager();
        if (accountId == null && accManager != null && accManager.getActiveAccount() != null) {
            accountId = accManager.getActiveAccount().id;
        }

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        if (tdManager != null && accountId != null) {
            client = tdManager.getOrCreateClient(accountId);
        }
    }

    private void setupRecyclerView() {
        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactAdapter(this, client, false, this::openChatWithUser, null);
        rvContacts.setAdapter(adapter);
    }

    private void loadContacts() {
        if (client == null) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        if (contactsMap.isEmpty()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        client.getContacts(result -> {
            runOnUiThread(() -> {
                swipeRefresh.setRefreshing(false);
                progressBar.setVisibility(View.GONE);

                if (result instanceof TdApi.Users) {
                    long[] userIds = ((TdApi.Users) result).userIds;
                    toolbar.setSubtitle(userIds.length + " contacts");
                    if (userIds.length == 0) {
                        applyFilterAndSort();
                        return;
                    }

                    for (long uid : userIds) {
                        client.getUser(uid, uRes -> {
                            if (uRes instanceof TdApi.User) {
                                TdApi.User u = (TdApi.User) uRes;
                                contactsMap.put(u.id, u);
                                runOnUiThread(this::applyFilterAndSort);
                            }
                        });
                    }
                } else if (result instanceof TdApi.Error) {
                    Toast.makeText(this, "Failed to load contacts: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                    applyFilterAndSort();
                }
            });
        });
    }

    private synchronized void applyFilterAndSort() {
        List<TdApi.User> filtered = new ArrayList<>();
        for (TdApi.User u : contactsMap.values()) {
            String name = (u.firstName != null ? u.firstName : "") + " " + (u.lastName != null ? u.lastName : "");
            name = name.toLowerCase(Locale.getDefault());
            String phone = u.phoneNumber != null ? u.phoneNumber : "";

            if (currentSearchQuery.isEmpty() || name.contains(currentSearchQuery) || phone.contains(currentSearchQuery)) {
                filtered.add(u);
            }
        }

        Collections.sort(filtered, (u1, u2) -> {
            String n1 = (u1.firstName != null ? u1.firstName : "") + (u1.lastName != null ? u1.lastName : "");
            String n2 = (u2.firstName != null ? u2.firstName : "") + (u2.lastName != null ? u2.lastName : "");
            return n1.compareToIgnoreCase(n2);
        });

        displayUsers.clear();
        displayUsers.addAll(filtered);
        adapter.updateUsers(displayUsers);

        if (displayUsers.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            rvContacts.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            rvContacts.setVisibility(View.VISIBLE);
        }
    }

    private void openChatWithUser(TdApi.User user) {
        if (client == null || user == null) return;
        progressBar.setVisibility(View.VISIBLE);

        client.createPrivateChat(user.id, result -> {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (result instanceof TdApi.Chat) {
                    TdApi.Chat chat = (TdApi.Chat) result;
                    Intent intent = new Intent(ContactsActivity.this, ChatActivity.class);
                    intent.putExtra("chat_id", chat.id);
                    intent.putExtra("chat_title", chat.title != null ? chat.title : user.firstName);
                    intent.putExtra("account_id", accountId);
                    startActivity(intent);
                } else if (result instanceof TdApi.Error) {
                    Toast.makeText(this, "Cannot open chat: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showAddContactDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);
        EditText etFirst = dialogView.findViewById(R.id.etAddContactFirst);
        EditText etLast = dialogView.findViewById(R.id.etAddContactLast);
        EditText etPhone = dialogView.findViewById(R.id.etAddContactPhone);

        new AlertDialog.Builder(this)
                .setTitle("New Contact")
                .setView(dialogView)
                .setPositiveButton("Add", (dialog, which) -> {
                    String first = etFirst.getText().toString().trim();
                    String last = etLast.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();

                    if (phone.isEmpty()) {
                        Toast.makeText(this, "Phone number is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (first.isEmpty()) {
                        first = phone;
                    }

                    if (!phone.startsWith("+")) {
                        phone = "+" + phone;
                    }

                    TdApi.ImportedContact importedContact = new TdApi.ImportedContact(phone, first, last, null);
                    client.send(new TdApi.ImportContacts(new TdApi.ImportedContact[]{importedContact}), res -> {
                        runOnUiThread(() -> {
                            if (res instanceof TdApi.ImportedContacts) {
                                Toast.makeText(this, "Contact added successfully", Toast.LENGTH_SHORT).show();
                                loadContacts();
                            } else if (res instanceof TdApi.Error) {
                                Toast.makeText(this, "Failed to add contact: " + ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openSearch() {
        layoutSearch.setVisibility(View.VISIBLE);
        etSearch.requestFocus();
    }

    private void closeSearch() {
        currentSearchQuery = "";
        etSearch.setText("");
        layoutSearch.setVisibility(View.GONE);
        applyFilterAndSort();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
