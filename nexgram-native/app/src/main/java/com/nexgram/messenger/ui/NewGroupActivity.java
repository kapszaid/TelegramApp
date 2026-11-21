package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
import java.util.Set;
import org.drinkless.tdlib.TdApi;

public class NewGroupActivity extends AppCompatActivity {
    private static final String TAG = "NewGroupActivity";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private RecyclerView rvContacts;
    private ProgressBar progressBar;
    private FloatingActionButton fabNext;

    private ContactAdapter adapter;
    private final List<TdApi.User> userList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_group);

        initViews();
        resolveAccountClient();
        setupRecyclerView();
        loadContacts();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarNewGroup);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvContacts = findViewById(R.id.rvGroupContacts);
        progressBar = findViewById(R.id.pbNewGroup);
        fabNext = findViewById(R.id.fabNextGroup);

        fabNext.setOnClickListener(v -> promptGroupNameAndCreate());
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
        adapter = new ContactAdapter(this, client, true, null, selectedIds -> {
            int count = selectedIds.size();
            if (count > 0) {
                toolbar.setSubtitle(count + " selected");
                fabNext.setVisibility(View.VISIBLE);
            } else {
                toolbar.setSubtitle("up to 200,000 members");
                fabNext.setVisibility(View.GONE);
            }
        });
        rvContacts.setAdapter(adapter);
    }

    private void loadContacts() {
        if (client == null) return;
        progressBar.setVisibility(View.VISIBLE);

        client.getContacts(result -> {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (result instanceof TdApi.Users) {
                    long[] ids = ((TdApi.Users) result).userIds;
                    for (long uid : ids) {
                        client.getUser(uid, uRes -> {
                            if (uRes instanceof TdApi.User) {
                                userList.add((TdApi.User) uRes);
                                Collections.sort(userList, (u1, u2) -> {
                                    String n1 = (u1.firstName != null ? u1.firstName : "");
                                    String n2 = (u2.firstName != null ? u2.firstName : "");
                                    return n1.compareToIgnoreCase(n2);
                                });
                                runOnUiThread(() -> adapter.updateUsers(userList));
                            }
                        });
                    }
                }
            });
        });
    }

    private void promptGroupNameAndCreate() {
        Set<Long> selected = adapter.getSelectedUserIds();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least 1 member", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);
        dialogView.findViewById(R.id.etAddContactLast).setVisibility(View.GONE);
        dialogView.findViewById(R.id.etAddContactPhone).setVisibility(View.GONE);
        EditText etTitle = dialogView.findViewById(R.id.etAddContactFirst);
        etTitle.setHint("Group Name");

        new AlertDialog.Builder(this)
                .setTitle("Enter Group Name")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String title = etTitle.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    long[] userIds = new long[selected.size()];
                    int idx = 0;
                    for (Long uid : selected) {
                        userIds[idx++] = uid;
                    }

                    progressBar.setVisibility(View.VISIBLE);
                    client.createNewBasicGroup(userIds, title, result -> {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            if (result instanceof TdApi.CreatedBasicGroupChat) {
                                TdApi.CreatedBasicGroupChat created = (TdApi.CreatedBasicGroupChat) result;
                                Toast.makeText(this, "Group \"" + title + "\" created!", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(NewGroupActivity.this, ChatActivity.class);
                                intent.putExtra("chat_id", created.chatId);
                                intent.putExtra("chat_title", title);
                                intent.putExtra("account_id", accountId);
                                startActivity(intent);
                                finish();
                            } else if (result instanceof TdApi.Error) {
                                Toast.makeText(this, "Failed to create group: " + ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
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
