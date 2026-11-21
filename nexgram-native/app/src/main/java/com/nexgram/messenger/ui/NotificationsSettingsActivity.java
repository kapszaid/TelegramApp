package com.nexgram.messenger.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;

import org.drinkless.tdlib.TdApi;

public class NotificationsSettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "nexgram_notification_prefs";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private CardView cardPermissionWarning;
    private Button btnGrantPermission;

    private SwitchMaterial switchPrivateChats;
    private SwitchMaterial switchGroupChats;
    private SwitchMaterial switchChannelChats;

    private SwitchMaterial switchInAppSounds;
    private SwitchMaterial switchInAppVibrate;
    private SwitchMaterial switchInAppPreview;

    private ProgressBar progressBar;

    private SharedPreferences prefs;

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                updateSystemPermissionCard();
                if (isGranted) {
                    Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Notification permission was denied", Toast.LENGTH_SHORT).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications_settings);

        accountId = getIntent().getStringExtra("account_id");
        resolveAccountClient();
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews();
        loadSettings();
    }

    private void resolveAccountClient() {
        if (accountId == null || accountId.isEmpty()) {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            if (active != null) {
                accountId = active.id;
            }
        }
        if (accountId != null) {
            client = TdLibManager.getInstance().getClient(accountId);
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarNotifications);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        cardPermissionWarning = findViewById(R.id.cardSystemPermissionWarning);
        btnGrantPermission = findViewById(R.id.btnEnableSystemNotifications);

        switchPrivateChats = findViewById(R.id.switchPrivateChats);
        switchGroupChats = findViewById(R.id.switchGroupChats);
        switchChannelChats = findViewById(R.id.switchChannelChats);

        switchInAppSounds = findViewById(R.id.switchInAppSounds);
        switchInAppVibrate = findViewById(R.id.switchInAppVibrate);
        switchInAppPreview = findViewById(R.id.switchInAppPreview);

        progressBar = findViewById(R.id.pbNotifications);

        btnGrantPermission.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        });

        // In-App preferences
        switchInAppSounds.setChecked(prefs.getBoolean("in_app_sounds", true));
        switchInAppVibrate.setChecked(prefs.getBoolean("in_app_vibrate", true));
        switchInAppPreview.setChecked(prefs.getBoolean("in_app_preview", true));

        switchInAppSounds.setOnCheckedChangeListener((btn, checked) -> prefs.edit().putBoolean("in_app_sounds", checked).apply());
        switchInAppVibrate.setOnCheckedChangeListener((btn, checked) -> prefs.edit().putBoolean("in_app_vibrate", checked).apply());
        switchInAppPreview.setOnCheckedChangeListener((btn, checked) -> prefs.edit().putBoolean("in_app_preview", checked).apply());

        // Scope Listeners
        switchPrivateChats.setOnCheckedChangeListener((btn, checked) -> {
            if (btn.isPressed()) updateScopeSetting(new TdApi.NotificationSettingsScopePrivateChats(), checked);
        });
        switchGroupChats.setOnCheckedChangeListener((btn, checked) -> {
            if (btn.isPressed()) updateScopeSetting(new TdApi.NotificationSettingsScopeGroupChats(), checked);
        });
        switchChannelChats.setOnCheckedChangeListener((btn, checked) -> {
            if (btn.isPressed()) updateScopeSetting(new TdApi.NotificationSettingsScopeChannelChats(), checked);
        });

        updateSystemPermissionCard();
    }

    private void updateSystemPermissionCard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            cardPermissionWarning.setVisibility(granted ? View.GONE : View.VISIBLE);
        } else {
            cardPermissionWarning.setVisibility(View.GONE);
        }
    }

    private void loadSettings() {
        if (client == null) return;
        progressBar.setVisibility(View.VISIBLE);

        // Load Private chats scope
        client.getScopeNotificationSettings(new TdApi.NotificationSettingsScopePrivateChats(), res -> runOnUiThread(() -> {
            if (res instanceof TdApi.ScopeNotificationSettings) {
                switchPrivateChats.setChecked(((TdApi.ScopeNotificationSettings) res).muteFor == 0);
            }
        }));

        // Load Group chats scope
        client.getScopeNotificationSettings(new TdApi.NotificationSettingsScopeGroupChats(), res -> runOnUiThread(() -> {
            if (res instanceof TdApi.ScopeNotificationSettings) {
                switchGroupChats.setChecked(((TdApi.ScopeNotificationSettings) res).muteFor == 0);
            }
        }));

        // Load Channel chats scope
        client.getScopeNotificationSettings(new TdApi.NotificationSettingsScopeChannelChats(), res -> runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (res instanceof TdApi.ScopeNotificationSettings) {
                switchChannelChats.setChecked(((TdApi.ScopeNotificationSettings) res).muteFor == 0);
            }
        }));
    }

    private void updateScopeSetting(TdApi.NotificationSettingsScope scope, boolean enabled) {
        if (client == null) return;
        int muteFor = enabled ? 0 : Integer.MAX_VALUE;
        TdApi.ScopeNotificationSettings settings = new TdApi.ScopeNotificationSettings(
                muteFor, 0, true, false, false, 0, false, false, false
        );

        client.setScopeNotificationSettings(scope, settings, res -> runOnUiThread(() -> {
            if (res instanceof TdApi.Ok) {
                Toast.makeText(this, "Notification settings saved", Toast.LENGTH_SHORT).show();
            } else if (res instanceof TdApi.Error) {
                Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
            }
        }));
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
