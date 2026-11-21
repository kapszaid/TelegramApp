package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import org.drinkless.tdlib.TdApi;

public class NewChannelActivity extends AppCompatActivity {
    private static final String TAG = "NewChannelActivity";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private EditText etName;
    private EditText etDescription;
    private RadioGroup rgType;
    private RadioButton rbPublic;
    private RadioButton rbPrivate;
    private LinearLayout layoutPublicLink;
    private EditText etUsername;
    private Button btnCreate;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_channel);

        initViews();
        resolveAccountClient();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarNewChannel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        etName = findViewById(R.id.etChannelName);
        etDescription = findViewById(R.id.etChannelDescription);
        rgType = findViewById(R.id.rgChannelType);
        rbPublic = findViewById(R.id.rbPublicChannel);
        rbPrivate = findViewById(R.id.rbPrivateChannel);
        layoutPublicLink = findViewById(R.id.layoutPublicLink);
        etUsername = findViewById(R.id.etChannelUsername);
        btnCreate = findViewById(R.id.btnCreateChannel);
        progressBar = findViewById(R.id.pbCreateChannel);

        rgType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbPublicChannel) {
                layoutPublicLink.setVisibility(View.VISIBLE);
            } else {
                layoutPublicLink.setVisibility(View.GONE);
            }
        });

        btnCreate.setOnClickListener(v -> createChannel());
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

    private void createChannel() {
        String name = etName.getText().toString().trim();
        String description = etDescription.getText().toString().trim();
        boolean isPublic = rbPublic.isChecked();
        String username = etUsername.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(this, "Channel name cannot be empty", Toast.LENGTH_SHORT).show();
            etName.requestFocus();
            return;
        }

        if (client == null) return;
        setLoading(true);

        client.createNewSupergroupOrChannel(name, true, description, result -> {
            runOnUiThread(() -> {
                if (result instanceof TdApi.Chat) {
                    TdApi.Chat chat = (TdApi.Chat) result;

                    // If public channel and username provided, set the public link
                    if (isPublic && !username.isEmpty() && chat.type instanceof TdApi.ChatTypeSupergroup) {
                        long supergroupId = ((TdApi.ChatTypeSupergroup) chat.type).supergroupId;
                        client.send(new TdApi.SetSupergroupUsername(supergroupId, username), userResult -> {
                            runOnUiThread(() -> {
                                setLoading(false);
                                Toast.makeText(this, "Channel \"" + name + "\" created!", Toast.LENGTH_SHORT).show();
                                openCreatedChat(chat.id, name);
                            });
                        });
                    } else {
                        setLoading(false);
                        Toast.makeText(this, "Channel \"" + name + "\" created!", Toast.LENGTH_SHORT).show();
                        openCreatedChat(chat.id, name);
                    }
                } else if (result instanceof TdApi.Error) {
                    setLoading(false);
                    Toast.makeText(this, "Failed to create channel: " + ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void openCreatedChat(long chatId, String title) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chat_id", chatId);
        intent.putExtra("chat_title", title);
        intent.putExtra("account_id", accountId);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnCreate.setEnabled(!loading);
        etName.setEnabled(!loading);
        etDescription.setEnabled(!loading);
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
